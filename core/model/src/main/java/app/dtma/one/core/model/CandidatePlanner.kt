package app.dtma.one.core.model

/**
 * Builds the ordered race set for PAER without letting RVEC displace all fresh DNS candidates.
 */
object CandidatePlanner {

    fun plan(
        dnsCandidates: List<EndpointCandidate>,
        rvecCandidates: List<EndpointCandidate>,
        nowMs: Long,
        currentContextId: String,
        preferences: ContextPreferences = ContextPreferences(),
        weights: ScoringWeights = ScoringWeights.DEFAULT,
        limits: PaerLimits = PaerLimits(),
    ): List<EndpointCandidate> {
        val fresh = dnsCandidates
            .filter { it.source == CandidateSource.CURRENT_DNS || it.source == CandidateSource.HTTPS_SVCB }
            .map { ScoreCalculator.withDecayedScore(it, nowMs, currentContextId, preferences, weights) }

        val cache = rvecCandidates
            .filter { it.source == CandidateSource.RECENTLY_VALIDATED_CACHE }
            .filter { !CooldownPolicy.isInCooldown(it, nowMs) }
            .filter { it.consecutiveFailures < 2 || (it.cooldownUntil ?: 0L) <= nowMs }
            .groupBy { it.hostname.lowercase() }
            .flatMap { (_, list) ->
                list
                    .map { ScoreCalculator.withDecayedScore(it, nowMs, currentContextId, preferences, weights) }
                    .sortedByDescending { it.decayedScore }
                    .take(limits.maxCacheCandidatesPerHost)
            }

        // RVEC must not displace all fresh DNS candidates.
        val merged = LinkedHashMap<String, EndpointCandidate>()
        fun key(c: EndpointCandidate) =
            "${c.hostname.lowercase()}|${c.ipAddress}|${c.port}|${c.transport}"

        for (c in fresh.sortedByDescending { it.decayedScore }) {
            merged.putIfAbsent(key(c), c)
        }

        val maxCacheSlots = if (fresh.isEmpty()) {
            limits.maxCacheCandidatesPerHost
        } else {
            // Keep at least one fresh DNS slot preference: only add cache that doesn't replace all fresh.
            limits.maxCacheCandidatesPerHost
        }

        var addedCache = 0
        for (c in cache.sortedByDescending { it.decayedScore }) {
            if (addedCache >= maxCacheSlots) break
            val k = key(c)
            if (!merged.containsKey(k)) {
                // Never drop the last fresh DNS for a host solely to insert RVEC.
                merged[k] = c
                addedCache++
            }
        }

        // Stale RVEC must not replace a fresher DNS entry for same host+ip.
        val result = merged.values
            .groupBy { it.hostname.lowercase() }
            .flatMap { (_, list) ->
                val hasFresh = list.any {
                    it.source == CandidateSource.CURRENT_DNS || it.source == CandidateSource.HTTPS_SVCB
                }
                if (hasFresh) {
                    list.filterNot { c ->
                        c.source == CandidateSource.RECENTLY_VALIDATED_CACHE &&
                            list.any { f ->
                                (f.source == CandidateSource.CURRENT_DNS || f.source == CandidateSource.HTTPS_SVCB) &&
                                    f.ipAddress == c.ipAddress &&
                                    f.port == c.port
                            }
                    }
                } else {
                    list
                }
            }
            .map { ScoreCalculator.withDecayedScore(it, nowMs, currentContextId, preferences, weights) }
            .sortedByDescending { it.decayedScore }

        return result
    }
}
