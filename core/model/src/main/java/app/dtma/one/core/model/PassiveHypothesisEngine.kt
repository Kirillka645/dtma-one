package app.dtma.one.core.model

enum class Hypothesis {
    NORMAL_NETWORK_VARIATION,
    POSSIBLE_DNS_PROBLEM,
    ENDPOINT_SPECIFIC_FAILURE,
    IP_FAMILY_SPECIFIC_FAILURE,
    TRANSPORT_SPECIFIC_FAILURE,
    SECURE_HANDSHAKE_FAILURE,
    POSSIBLE_SERVICE_REJECTION,
    GENERAL_NETWORK_DEGRADATION,
    INSUFFICIENT_DATA,
}

enum class RecommendedAction {
    NONE,
    RETRY_LIMITED,
    USE_ALTERNATE_FAMILY,
    USE_ALTERNATE_TRANSPORT,
    COOLDOWN,
    WAIT_FOR_NETWORK,
    STOP_ENDPOINT_RETRIES,
}

data class HypothesisResult(
    val primaryHypothesis: Hypothesis,
    val confidence: Double,
    val alternativeHypotheses: List<Hypothesis>,
    val insufficientData: Boolean,
    val recommendedAction: RecommendedAction,
    val cooldownUntil: Long?,
    val humanReadableExplanation: String,
)

data class Observation(
    val stage: FailureStage? = null,
    val httpStatus: Int? = null,
    val ipFamily: IpFamily? = null,
    val transport: Transport? = null,
    val success: Boolean = false,
    val distinctEndpointsTried: Int = 0,
    val distinctFamiliesTried: Int = 0,
    val dnsEmpty: Boolean = false,
    val dnsNoResponse: Boolean = false,
)

/**
 * Passive probabilistic assessment. Never claims proven blocking, forged RST, or MITM.
 */
class PassiveHypothesisEngine {

    fun evaluate(observations: List<Observation>, nowMs: Long = System.currentTimeMillis()): HypothesisResult {
        if (observations.isEmpty() || observations.all { it.distinctEndpointsTried == 0 && !it.success }) {
            return HypothesisResult(
                primaryHypothesis = Hypothesis.INSUFFICIENT_DATA,
                confidence = 0.0,
                alternativeHypotheses = emptyList(),
                insufficientData = true,
                recommendedAction = RecommendedAction.NONE,
                cooldownUntil = null,
                humanReadableExplanation =
                    "Недостаточно данных для оценки. Possibly temporary; no blocking claim is made.",
            )
        }

        val failures = observations.filter { !it.success }
        val successes = observations.filter { it.success }

        // HTTP 401/403 is network success but application rejection — check before "all OK".
        if (observations.any { it.httpStatus == 401 || it.httpStatus == 403 }) {
            return HypothesisResult(
                primaryHypothesis = Hypothesis.POSSIBLE_SERVICE_REJECTION,
                confidence = 0.7,
                alternativeHypotheses = listOf(Hypothesis.ENDPOINT_SPECIFIC_FAILURE),
                insufficientData = false,
                recommendedAction = RecommendedAction.STOP_ENDPOINT_RETRIES,
                cooldownUntil = nowMs + 5 * 60_000L,
                humanReadableExplanation =
                    "Сервис, вероятно, отклонил запрос (401/403). Endpoint retries остановлены; " +
                        "это не обязательно сетевая блокировка.",
            )
        }

        if (successes.isNotEmpty() && failures.isEmpty()) {
            return HypothesisResult(
                primaryHypothesis = Hypothesis.NORMAL_NETWORK_VARIATION,
                confidence = 0.6,
                alternativeHypotheses = listOf(Hypothesis.INSUFFICIENT_DATA),
                insufficientData = false,
                recommendedAction = RecommendedAction.NONE,
                cooldownUntil = null,
                humanReadableExplanation =
                    "Соединения выглядят нормально. Possible normal network variation.",
            )
        }

        if (observations.any { it.dnsNoResponse || it.dnsEmpty }) {
            return HypothesisResult(
                primaryHypothesis = Hypothesis.POSSIBLE_DNS_PROBLEM,
                confidence = 0.55,
                alternativeHypotheses = listOf(Hypothesis.GENERAL_NETWORK_DEGRADATION, Hypothesis.INSUFFICIENT_DATA),
                insufficientData = observations.size < 2,
                recommendedAction = RecommendedAction.RETRY_LIMITED,
                cooldownUntil = null,
                humanReadableExplanation =
                    "Возможна проблема DNS (no response / empty). Это не доказательство блокировки.",
            )
        }

        if (failures.any {
                it.stage == FailureStage.TLS_NOT_COMPLETED ||
                    it.stage == FailureStage.TLS_VALIDATION_FAILED ||
                    it.stage == FailureStage.ALPN_MISMATCH
            }
        ) {
            return HypothesisResult(
                primaryHypothesis = Hypothesis.SECURE_HANDSHAKE_FAILURE,
                confidence = 0.5,
                alternativeHypotheses = listOf(Hypothesis.ENDPOINT_SPECIFIC_FAILURE, Hypothesis.INSUFFICIENT_DATA),
                insufficientData = failures.size < 2,
                recommendedAction = RecommendedAction.COOLDOWN,
                cooldownUntil = nowMs + CooldownPolicy.cooldownDurationMs(1),
                humanReadableExplanation =
                    "Возможен сбой защищённого рукопожатия. Точная причина TLS alert недоступна " +
                        "без перехвата; блокировка не утверждается.",
            )
        }

        val familyFailures = failures.mapNotNull { it.ipFamily }.groupingBy { it }.eachCount()
        if (familyFailures.size == 1 && observations.mapNotNull { it.ipFamily }.toSet().size > 1) {
            return HypothesisResult(
                primaryHypothesis = Hypothesis.IP_FAMILY_SPECIFIC_FAILURE,
                confidence = 0.45,
                alternativeHypotheses = listOf(Hypothesis.ENDPOINT_SPECIFIC_FAILURE, Hypothesis.INSUFFICIENT_DATA),
                insufficientData = false,
                recommendedAction = RecommendedAction.USE_ALTERNATE_FAMILY,
                cooldownUntil = null,
                humanReadableExplanation =
                    "Вероятны проблемы с одним семейством IP (IPv4/IPv6). Возможно, другой путь доступен.",
            )
        }

        if (failures.size >= 2 && successes.isEmpty()) {
            return HypothesisResult(
                primaryHypothesis = Hypothesis.GENERAL_NETWORK_DEGRADATION,
                confidence = 0.4,
                alternativeHypotheses = listOf(Hypothesis.ENDPOINT_SPECIFIC_FAILURE, Hypothesis.INSUFFICIENT_DATA),
                insufficientData = false,
                recommendedAction = RecommendedAction.WAIT_FOR_NETWORK,
                cooldownUntil = nowMs + CooldownPolicy.cooldownDurationMs(2),
                humanReadableExplanation =
                    "Возможна общая деградация сети. Недостаточно данных, чтобы утверждать блокировку.",
            )
        }

        if (failures.any { it.stage == FailureStage.TRANSPORT_REFUSED || it.stage == FailureStage.TRANSPORT_TIMEOUT }) {
            return HypothesisResult(
                primaryHypothesis = Hypothesis.ENDPOINT_SPECIFIC_FAILURE,
                confidence = 0.4,
                alternativeHypotheses = listOf(Hypothesis.NORMAL_NETWORK_VARIATION, Hypothesis.INSUFFICIENT_DATA),
                insufficientData = failures.size < 2,
                recommendedAction = RecommendedAction.RETRY_LIMITED,
                cooldownUntil = null,
                humanReadableExplanation =
                    "Возможен отказ конкретного endpoint. Это наблюдение, а не доказательство инъекции RST.",
            )
        }

        return HypothesisResult(
            primaryHypothesis = Hypothesis.INSUFFICIENT_DATA,
            confidence = 0.2,
            alternativeHypotheses = listOf(Hypothesis.NORMAL_NETWORK_VARIATION),
            insufficientData = true,
            recommendedAction = RecommendedAction.NONE,
            cooldownUntil = null,
            humanReadableExplanation = "Недостаточно данных для уверенной гипотезы.",
        )
    }
}

/**
 * Local metrics without traffic payload, URL path/query, headers, cookies, or certs.
 */
data class LocalMetrics(
    val timeToTransportMs: Long? = null,
    val timeToSecureChannelMs: Long? = null,
    val winnerSource: CandidateSource? = null,
    val winnerIpFamily: IpFamily? = null,
    val winnerTransport: Transport? = null,
    val numberOfStartedCandidates: Int = 0,
    val numberOfCancelledCandidates: Int = 0,
    val numberOfFailedCandidates: Int = 0,
    val rvecWinRate: Double? = null,
    val staleRvecFailureRate: Double? = null,
    val falseTransportSuccessRate: Double? = null,
    val averageRaceDurationMs: Double? = null,
    val cooldownActivationRate: Double? = null,
    val insufficientDataRate: Double? = null,
)
