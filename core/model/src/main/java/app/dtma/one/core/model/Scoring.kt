package app.dtma.one.core.model

import kotlin.math.exp
import kotlin.math.ln

/**
 * Configurable MVP scoring weights for PAER candidate ranking.
 */
data class ScoringWeights(
    val currentDns: Double = 100.0,
    val httpsSvcb: Double = 95.0,
    val rvec: Double = 40.0,
    val successCurrentContext1h: Double = 35.0,
    val successCurrentContext24h: Double = 20.0,
    val successOtherContext: Double = 5.0,
    val oneRecentFailure: Double = -40.0,
    val twoConsecutiveFailures: Double = -100.0,
    val successfulTransportCurrentContext: Double = 15.0,
    val successfulIpFamilyCurrentContext: Double = 10.0,
    val cacheHalfLifeHours: Double = DEFAULT_CACHE_HALF_LIFE_HOURS,
) {
    companion object {
        const val DEFAULT_CACHE_HALF_LIFE_HOURS = 6.0
        val DEFAULT = ScoringWeights()
    }
}

data class ContextPreferences(
    val preferredTransport: Transport? = null,
    val preferredIpFamily: IpFamily? = null,
    val networkContextId: String? = null,
)

object ScoreCalculator {
    private val LN2 = ln(2.0)

    /**
     * Correct exponential half-life decay:
     * cacheTimeFactor = exp(-ln(2) * ageHours / halfLifeHours)
     */
    fun cacheTimeFactor(ageHours: Double, halfLifeHours: Double): Double {
        require(halfLifeHours > 0.0) { "halfLifeHours must be positive" }
        if (ageHours <= 0.0) return 1.0
        return exp(-LN2 * ageHours / halfLifeHours)
    }

    fun decayedScore(baseCacheScore: Double, ageHours: Double, halfLifeHours: Double): Double {
        return baseCacheScore * cacheTimeFactor(ageHours, halfLifeHours)
    }

    fun baseSourceScore(source: CandidateSource, weights: ScoringWeights = ScoringWeights.DEFAULT): Double {
        return when (source) {
            CandidateSource.CURRENT_DNS -> weights.currentDns
            CandidateSource.HTTPS_SVCB -> weights.httpsSvcb
            CandidateSource.RECENTLY_VALIDATED_CACHE -> weights.rvec
        }
    }

    fun score(
        candidate: EndpointCandidate,
        nowMs: Long,
        currentContextId: String,
        preferences: ContextPreferences = ContextPreferences(),
        weights: ScoringWeights = ScoringWeights.DEFAULT,
    ): Double {
        var score = baseSourceScore(candidate.source, weights)

        if (candidate.source == CandidateSource.RECENTLY_VALIDATED_CACHE) {
            val successAt = candidate.lastSuccessAt
            if (successAt != null) {
                val ageHours = (nowMs - successAt).coerceAtLeast(0L) / 3_600_000.0
                score = decayedScore(weights.rvec, ageHours, weights.cacheHalfLifeHours)
            }
        }

        val lastSuccess = candidate.lastSuccessAt
        if (lastSuccess != null) {
            val ageMs = nowMs - lastSuccess
            val sameContext = candidate.networkContextId == currentContextId
            when {
                sameContext && ageMs <= 3_600_000L -> score += weights.successCurrentContext1h
                sameContext && ageMs <= 86_400_000L -> score += weights.successCurrentContext24h
                !sameContext -> score += weights.successOtherContext
            }
        }

        when (candidate.consecutiveFailures) {
            0 -> Unit
            1 -> score += weights.oneRecentFailure
            else -> score += weights.twoConsecutiveFailures
        }

        if (preferences.networkContextId == currentContextId) {
            if (preferences.preferredTransport == candidate.transport) {
                score += weights.successfulTransportCurrentContext
            }
            if (preferences.preferredIpFamily == candidate.ipFamily) {
                score += weights.successfulIpFamilyCurrentContext
            }
        }

        return score
    }

    fun withDecayedScore(
        candidate: EndpointCandidate,
        nowMs: Long,
        currentContextId: String,
        preferences: ContextPreferences = ContextPreferences(),
        weights: ScoringWeights = ScoringWeights.DEFAULT,
    ): EndpointCandidate {
        val s = score(candidate, nowMs, currentContextId, preferences, weights)
        return candidate.copy(decayedScore = s)
    }
}

object CooldownPolicy {
    fun cooldownDurationMs(consecutiveFailures: Int, maxHours: Int = PaerLimits.MAX_COOLDOWN_HOURS): Long {
        val hoursCapMs = maxHours * 3_600_000L
        return when {
            consecutiveFailures <= 0 -> 0L
            consecutiveFailures == 1 -> 5 * 60_000L
            consecutiveFailures == 2 -> 30 * 60_000L
            consecutiveFailures == 3 -> 6 * 3_600_000L
            else -> hoursCapMs
        }.coerceAtMost(hoursCapMs)
    }

    fun isInCooldown(candidate: EndpointCandidate, nowMs: Long): Boolean {
        val until = candidate.cooldownUntil ?: return false
        return until > nowMs
    }
}
