package app.dtma.one.core.model

/**
 * Pure logic for limited endpoint racing (no I/O).
 * Ensures width, attempt, and background-probe limits.
 */
class RaceCoordinatorLogic(
    private val config: RaceConfig = RaceConfig(),
    private val limits: PaerLimits = PaerLimits(),
) {
    data class RacePlan(
        val scheduled: List<ScheduledCandidate>,
        val totalAttemptsAllowed: Int,
    )

    data class ScheduledCandidate(
        val candidate: EndpointCandidate,
        val startDelayMs: Long,
        val attemptIndex: Int,
    )

    fun buildPlan(candidates: List<EndpointCandidate>): RacePlan {
        require(limits.activeBackgroundProbes == 0) {
            "ACTIVE_BACKGROUND_PROBES must remain 0"
        }
        val width = config.effectiveWidth().coerceAtMost(limits.maxSimultaneousCandidates)
        val maxAttempts = limits.maxTotalAttemptsPerRequest.coerceAtMost(PaerLimits.MAX_TOTAL_ATTEMPTS_PER_REQUEST)
        val take = candidates.take(minOf(width, maxAttempts, candidates.size))
        val scheduled = take.mapIndexed { index, c ->
            ScheduledCandidate(
                candidate = c.copy(currentState = CandidateState.SCHEDULED),
                startDelayMs = config.delayForIndex(index),
                attemptIndex = index,
            )
        }
        return RacePlan(scheduled = scheduled, totalAttemptsAllowed = maxAttempts)
    }

    fun canStartAnother(started: Int, simultaneousActive: Int, totalAttempts: Int): Boolean {
        if (started >= limits.maxTotalAttemptsPerRequest) return false
        if (totalAttempts >= limits.maxTotalAttemptsPerRequest) return false
        if (simultaneousActive >= config.effectiveWidth()) return false
        if (simultaneousActive >= limits.maxSimultaneousCandidates) return false
        return true
    }

    fun maxSimultaneous(): Int = minOf(config.effectiveWidth(), limits.maxSimultaneousCandidates)
}

/**
 * Ensures non-idempotent application requests are sent at most once.
 */
class ApplicationRequestGuard {
    @Volatile
    private var sent = false

    fun trySendOnce(block: () -> Unit): Boolean {
        synchronized(this) {
            if (sent) return false
            sent = true
        }
        block()
        return true
    }

    fun wasSent(): Boolean = sent

    fun reset() {
        sent = false
    }
}

/**
 * HTTP status handling: transport/TLS/HTTP success is not reduced for 4xx/5xx.
 */
object HttpStatusPolicy {
    fun isNetworkSuccess(statusCode: Int): Boolean = statusCode in 100..599

    fun shouldStopEndpointRetries(statusCode: Int): Boolean =
        statusCode == 401 || statusCode == 403

    fun shouldLowerEndpointScore(statusCode: Int): Boolean = false

    fun allowsIdempotentRetry(statusCode: Int): Boolean = statusCode in 500..599

    fun respectsRetryAfter(statusCode: Int): Boolean = statusCode == 429
}
