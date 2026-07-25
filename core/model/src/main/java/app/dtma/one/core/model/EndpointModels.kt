package app.dtma.one.core.model

/**
 * Legitimate endpoint candidate for PAER racing.
 * Does not store URL path, query, headers, cookies, or payload.
 */
data class EndpointCandidate(
    val hostname: String,
    val ipAddress: String,
    val ipFamily: IpFamily,
    val port: Int,
    val transport: Transport,
    val alpn: List<String> = emptyList(),
    val source: CandidateSource,
    val networkContextId: String,
    val discoveredAt: Long,
    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,
    val consecutiveFailures: Int = 0,
    val decayedScore: Double = 0.0,
    val currentState: CandidateState = CandidateState.NEW,
    val cooldownUntil: Long? = null,
)

enum class IpFamily { IPV4, IPV6 }

enum class Transport {
    TCP,
    /** QUIC racing is NOT_IMPLEMENTED in MVP; UDP is forwarded without content inspection. */
    UDP,
    QUIC_NOT_IMPLEMENTED,
}

enum class CandidateSource {
    CURRENT_DNS,
    HTTPS_SVCB,
    RECENTLY_VALIDATED_CACHE,
}

enum class CandidateState {
    NEW,
    SCHEDULED,
    CONNECTING,
    TRANSPORT_CONNECTED,
    SECURE_CHANNEL_READY,
    SELECTED,
    FAILED,
    CANCELLED,
    COOLDOWN,
}

enum class FailureStage {
    DNS_NO_RESPONSE,
    DNS_EMPTY,
    TRANSPORT_TIMEOUT,
    TRANSPORT_REFUSED,
    TRANSPORT_RESET,
    TLS_NOT_COMPLETED,
    TLS_VALIDATION_FAILED,
    ALPN_MISMATCH,
    APPLICATION_TIMEOUT,
    APPLICATION_REJECTED,
    NETWORK_LOST,
    CANCELLED_BY_RACE,
    UNKNOWN_FAILURE,
}

enum class NetworkType { WIFI, CELLULAR, ETHERNET, OTHER, UNKNOWN }

/**
 * Network context without SSID, BSSID, MAC, IMEI, IMSI, or SIM identifiers.
 */
data class NetworkContext(
    val id: String,
    val networkType: NetworkType,
    val androidNetworkId: String?,
    val hasIpv4: Boolean,
    val hasIpv6: Boolean,
    val dnsFingerprint: String?,
    val createdAt: Long,
)

enum class VpnUiState {
    OFF,
    STARTING,
    ACTIVE,
    LIMITED,
    UNSTABLE,
    ERROR,
}

data class PaerLimits(
    val maxSimultaneousCandidates: Int = MAX_SIMULTANEOUS_CANDIDATES,
    val defaultSimultaneousCandidates: Int = DEFAULT_SIMULTANEOUS_CANDIDATES,
    val maxTotalAttemptsPerRequest: Int = MAX_TOTAL_ATTEMPTS_PER_REQUEST,
    val maxRetryRounds: Int = MAX_RETRY_ROUNDS,
    val maxCacheCandidatesPerHost: Int = MAX_CACHE_CANDIDATES_PER_HOST,
    val maxGlobalRvecEntries: Int = MAX_GLOBAL_RVEC_ENTRIES,
    val maxCooldownHours: Int = MAX_COOLDOWN_HOURS,
    val logRetentionHours: Int = LOG_RETENTION_HOURS,
    val activeBackgroundProbes: Int = ACTIVE_BACKGROUND_PROBES,
) {
    companion object {
        const val MAX_SIMULTANEOUS_CANDIDATES = 3
        const val DEFAULT_SIMULTANEOUS_CANDIDATES = 2
        const val MAX_TOTAL_ATTEMPTS_PER_REQUEST = 4
        const val MAX_RETRY_ROUNDS = 1
        const val MAX_CACHE_CANDIDATES_PER_HOST = 4
        const val MAX_GLOBAL_RVEC_ENTRIES = 1000
        const val MAX_COOLDOWN_HOURS = 24
        const val LOG_RETENTION_HOURS = 24
        const val ACTIVE_BACKGROUND_PROBES = 0
    }
}

data class RaceConfig(
    val width: Int = PaerLimits.DEFAULT_SIMULTANEOUS_CANDIDATES,
    val secondStartDelayMs: Long = 250L,
    val thirdStartDelayMs: Long = 750L,
    val batterySaver: Boolean = false,
    val halfLifeHours: Double = ScoringWeights.DEFAULT_CACHE_HALF_LIFE_HOURS,
) {
    fun effectiveWidth(): Int {
        val max = if (batterySaver) 2 else PaerLimits.MAX_SIMULTANEOUS_CANDIDATES
        return width.coerceIn(1, max)
    }

    fun delayForIndex(index: Int): Long = when {
        batterySaver -> when (index) {
            0 -> 0L
            else -> 500L
        }
        else -> when (index) {
            0 -> 0L
            1 -> secondStartDelayMs
            2 -> thirdStartDelayMs
            else -> thirdStartDelayMs
        }
    }
}
