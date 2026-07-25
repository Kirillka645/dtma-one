package app.dtma.one.core.model

/**
 * RVEC mutation policy: cache complements DNS and never stores URL path/content.
 */
object RvecPolicy {

    data class RvecRecord(
        val hostname: String,
        val ipAddress: String,
        val ipFamily: IpFamily,
        val port: Int,
        val transport: Transport,
        val alpn: List<String>,
        val networkContextId: String,
        val lastSuccessAt: Long?,
        val lastFailureAt: Long?,
        val consecutiveFailures: Int,
        val cooldownUntil: Long?,
        val discoveredAt: Long,
    ) {
        init {
            require(!hostname.contains('/')) { "hostname must not contain path" }
            require(!hostname.contains('?')) { "hostname must not contain query" }
        }

        fun toCandidate(): EndpointCandidate = EndpointCandidate(
            hostname = hostname,
            ipAddress = ipAddress,
            ipFamily = ipFamily,
            port = port,
            transport = transport,
            alpn = alpn,
            source = CandidateSource.RECENTLY_VALIDATED_CACHE,
            networkContextId = networkContextId,
            discoveredAt = discoveredAt,
            lastSuccessAt = lastSuccessAt,
            lastFailureAt = lastFailureAt,
            consecutiveFailures = consecutiveFailures,
            cooldownUntil = cooldownUntil,
            currentState = if (cooldownUntil != null && (cooldownUntil > 0)) {
                CandidateState.COOLDOWN
            } else {
                CandidateState.NEW
            },
        )
    }

    fun onSuccess(record: RvecRecord, nowMs: Long, contextId: String): RvecRecord {
        return record.copy(
            lastSuccessAt = nowMs,
            lastFailureAt = record.lastFailureAt,
            consecutiveFailures = 0,
            cooldownUntil = null,
            networkContextId = contextId,
        )
    }

    /**
     * CANCELLED_BY_RACE is not a failure.
     */
    fun onFailure(
        record: RvecRecord,
        nowMs: Long,
        stage: FailureStage,
        contextId: String,
    ): RvecRecord {
        if (stage == FailureStage.CANCELLED_BY_RACE) {
            return record
        }
        val failures = record.consecutiveFailures + 1
        val cooldown = if (failures >= 2) {
            nowMs + CooldownPolicy.cooldownDurationMs(failures)
        } else {
            nowMs + CooldownPolicy.cooldownDurationMs(1)
        }
        return record.copy(
            lastFailureAt = nowMs,
            consecutiveFailures = failures,
            cooldownUntil = if (failures >= 2) cooldown else record.cooldownUntil,
            networkContextId = contextId,
        )
    }

    fun fromCandidate(c: EndpointCandidate): RvecRecord = RvecRecord(
        hostname = c.hostname.substringBefore('/').substringBefore('?'),
        ipAddress = c.ipAddress,
        ipFamily = c.ipFamily,
        port = c.port,
        transport = c.transport,
        alpn = c.alpn,
        networkContextId = c.networkContextId,
        lastSuccessAt = c.lastSuccessAt,
        lastFailureAt = c.lastFailureAt,
        consecutiveFailures = c.consecutiveFailures,
        cooldownUntil = c.cooldownUntil,
        discoveredAt = c.discoveredAt,
    )
}
