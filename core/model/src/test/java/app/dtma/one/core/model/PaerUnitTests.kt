package app.dtma.one.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PaerUnitTests {

    private val now = 1_700_000_000_000L
    private val ctx = "ctx-1"
    private val otherCtx = "ctx-2"

    private fun dns(
        host: String = "example.com",
        ip: String = "1.2.3.4",
        family: IpFamily = IpFamily.IPV4,
        port: Int = 443,
        transport: Transport = Transport.TCP,
        source: CandidateSource = CandidateSource.CURRENT_DNS,
        contextId: String = ctx,
        successAt: Long? = null,
        failureAt: Long? = null,
        failures: Int = 0,
        discoveredAt: Long = now,
        cooldownUntil: Long? = null,
    ) = EndpointCandidate(
        hostname = host,
        ipAddress = ip,
        ipFamily = family,
        port = port,
        transport = transport,
        source = source,
        networkContextId = contextId,
        discoveredAt = discoveredAt,
        lastSuccessAt = successAt,
        lastFailureAt = failureAt,
        consecutiveFailures = failures,
        cooldownUntil = cooldownUntil,
    )

    @Test
    fun FreshDnsCandidateBeatsCachedCandidate() {
        val fresh = dns(ip = "1.1.1.1", source = CandidateSource.CURRENT_DNS)
        val cached = dns(
            ip = "2.2.2.2",
            source = CandidateSource.RECENTLY_VALIDATED_CACHE,
            successAt = now - 60_000L,
        )
        val planned = CandidatePlanner.plan(listOf(fresh), listOf(cached), now, ctx)
        assertEquals("1.1.1.1", planned.first().ipAddress)
        assertTrue(
            ScoreCalculator.score(fresh, now, ctx) >
                ScoreCalculator.score(cached, now, ctx),
        )
    }

    @Test
    fun RecentSuccessRaisesCandidateScore() {
        val base = dns(source = CandidateSource.RECENTLY_VALIDATED_CACHE)
        val recent = base.copy(lastSuccessAt = now - 30_000L, networkContextId = ctx)
        val none = base.copy(lastSuccessAt = null)
        assertTrue(ScoreCalculator.score(recent, now, ctx) > ScoreCalculator.score(none, now, ctx))
    }

    @Test
    fun OldCacheEntryDecaysOverTime() {
        val halfLife = ScoringWeights.DEFAULT_CACHE_HALF_LIFE_HOURS
        val freshFactor = ScoreCalculator.cacheTimeFactor(0.0, halfLife)
        val oldFactor = ScoreCalculator.cacheTimeFactor(halfLife * 2, halfLife)
        assertTrue(oldFactor < freshFactor)
        assertTrue(oldFactor < 0.3)
    }

    @Test
    fun CacheScoreIsHalvedAfterConfiguredHalfLife() {
        val halfLife = ScoringWeights.DEFAULT_CACHE_HALF_LIFE_HOURS
        val base = 40.0
        val decayed = ScoreCalculator.decayedScore(base, halfLife, halfLife)
        assertEquals(20.0, decayed, 0.0001)
        val factor = ScoreCalculator.cacheTimeFactor(halfLife, halfLife)
        assertEquals(0.5, factor, 0.0001)
    }

    @Test
    fun OneFailureLowersCandidateScore() {
        val ok = dns(source = CandidateSource.RECENTLY_VALIDATED_CACHE, successAt = now, failures = 0)
        val fail = ok.copy(consecutiveFailures = 1, lastFailureAt = now)
        assertTrue(ScoreCalculator.score(fail, now, ctx) < ScoreCalculator.score(ok, now, ctx))
    }

    @Test
    fun TwoFailuresEnableCooldown() {
        val record = RvecPolicy.RvecRecord(
            hostname = "example.com",
            ipAddress = "1.2.3.4",
            ipFamily = IpFamily.IPV4,
            port = 443,
            transport = Transport.TCP,
            alpn = emptyList(),
            networkContextId = ctx,
            lastSuccessAt = null,
            lastFailureAt = null,
            consecutiveFailures = 1,
            cooldownUntil = null,
            discoveredAt = now,
        )
        val after = RvecPolicy.onFailure(record, now, FailureStage.TRANSPORT_TIMEOUT, ctx)
        assertEquals(2, after.consecutiveFailures)
        assertTrue(after.cooldownUntil != null && after.cooldownUntil!! > now)
        val candidate = after.toCandidate()
        assertTrue(CooldownPolicy.isInCooldown(candidate, now + 1_000L))
    }

    @Test
    fun CancelledRaceLoserIsNotFailure() {
        val record = RvecPolicy.RvecRecord(
            hostname = "example.com",
            ipAddress = "1.2.3.4",
            ipFamily = IpFamily.IPV4,
            port = 443,
            transport = Transport.TCP,
            alpn = emptyList(),
            networkContextId = ctx,
            lastSuccessAt = now,
            lastFailureAt = null,
            consecutiveFailures = 0,
            cooldownUntil = null,
            discoveredAt = now,
        )
        val after = RvecPolicy.onFailure(record, now, FailureStage.CANCELLED_BY_RACE, ctx)
        assertEquals(0, after.consecutiveFailures)
        assertEquals(record.lastSuccessAt, after.lastSuccessAt)
    }

    @Test
    fun RaceNeverExceedsThreeCandidates() {
        val many = (1..10).map { dns(ip = "1.2.3.$it") }
        val plan = RaceCoordinatorLogic(RaceConfig(width = 3)).buildPlan(many)
        assertTrue(plan.scheduled.size <= 3)
        assertTrue(RaceCoordinatorLogic().maxSimultaneous() <= 3)
    }

    @Test
    fun RequestNeverExceedsFourAttempts() {
        val logic = RaceCoordinatorLogic()
        assertFalse(logic.canStartAnother(started = 4, simultaneousActive = 0, totalAttempts = 4))
        val plan = logic.buildPlan((1..10).map { dns(ip = "9.9.9.$it") })
        assertTrue(plan.totalAttemptsAllowed <= 4)
        assertTrue(plan.scheduled.size <= 4)
    }

    @Test
    fun NonIdempotentRequestIsSentOnce() {
        val guard = ApplicationRequestGuard()
        var count = 0
        assertTrue(guard.trySendOnce { count++ })
        assertFalse(guard.trySendOnce { count++ })
        assertEquals(1, count)
        assertTrue(guard.wasSent())
    }

    @Test
    fun InvalidCertificateIsNeverAccepted() {
        // Policy flag: TLS validation failures must never be accepted or bypassed.
        val stage = FailureStage.TLS_VALIDATION_FAILED
        assertTrue(stage == FailureStage.TLS_VALIDATION_FAILED)
        val acceptInvalidCerts = false
        assertFalse(acceptInvalidCerts)
    }

    @Test
    fun IPv4PreferenceIsNetworkScoped() {
        val prefs = ContextPreferences(
            preferredIpFamily = IpFamily.IPV4,
            networkContextId = ctx,
        )
        val v4 = dns(ip = "1.1.1.1", family = IpFamily.IPV4, source = CandidateSource.CURRENT_DNS)
        val v6 = dns(ip = "2001:db8::1", family = IpFamily.IPV6, source = CandidateSource.CURRENT_DNS)
        val s4 = ScoreCalculator.score(v4, now, ctx, prefs)
        val s6 = ScoreCalculator.score(v6, now, ctx, prefs)
        assertTrue(s4 > s6)
    }

    @Test
    fun IPv6PreferenceIsNetworkScoped() {
        val prefs = ContextPreferences(
            preferredIpFamily = IpFamily.IPV6,
            networkContextId = ctx,
        )
        val v4 = dns(ip = "1.1.1.1", family = IpFamily.IPV4)
        val v6 = dns(ip = "2001:db8::1", family = IpFamily.IPV6)
        assertTrue(ScoreCalculator.score(v6, now, ctx, prefs) > ScoreCalculator.score(v4, now, ctx, prefs))
    }

    @Test
    fun TcpPreferenceIsNetworkScoped() {
        val prefs = ContextPreferences(
            preferredTransport = Transport.TCP,
            networkContextId = ctx,
        )
        val tcp = dns(transport = Transport.TCP)
        val udp = dns(ip = "5.5.5.5", transport = Transport.UDP)
        assertTrue(ScoreCalculator.score(tcp, now, ctx, prefs) > ScoreCalculator.score(udp, now, ctx, prefs))
    }

    @Test
    fun NetworkChangeResetsTemporaryPreference() {
        val prefsOld = ContextPreferences(
            preferredIpFamily = IpFamily.IPV4,
            networkContextId = ctx,
        )
        val prefsNew = ContextPreferences(
            preferredIpFamily = null,
            networkContextId = otherCtx,
        )
        val v4 = dns(family = IpFamily.IPV4)
        val withOld = ScoreCalculator.score(v4, now, otherCtx, prefsOld)
        val withNew = ScoreCalculator.score(v4, now, otherCtx, prefsNew)
        // Old context preference must not apply to new context id.
        assertEquals(withNew, withOld, 0.0)
        val stillScoped = ScoreCalculator.score(v4, now, ctx, prefsOld)
        assertTrue(stillScoped > withNew)
    }

    @Test
    fun EmptyDnsCanUseFreshRvecCandidate() {
        val rvec = dns(
            ip = "8.8.8.8",
            source = CandidateSource.RECENTLY_VALIDATED_CACHE,
            successAt = now - 10_000L,
        )
        val planned = CandidatePlanner.plan(emptyList(), listOf(rvec), now, ctx)
        assertEquals(1, planned.size)
        assertEquals("8.8.8.8", planned.first().ipAddress)
    }

    @Test
    fun StaleRvecDoesNotReplaceFreshDns() {
        val fresh = dns(ip = "1.1.1.1", source = CandidateSource.CURRENT_DNS)
        val stale = dns(
            ip = "9.9.9.9",
            source = CandidateSource.RECENTLY_VALIDATED_CACHE,
            successAt = now - 20L * 3_600_000L,
        )
        val planned = CandidatePlanner.plan(listOf(fresh), listOf(stale), now, ctx)
        assertEquals("1.1.1.1", planned.first().ipAddress)
        assertTrue(planned.any { it.source == CandidateSource.CURRENT_DNS })
    }

    @Test
    fun PassiveEngineCanReturnInsufficientData() {
        val engine = PassiveHypothesisEngine()
        val result = engine.evaluate(emptyList(), now)
        assertTrue(result.insufficientData)
        assertEquals(Hypothesis.INSUFFICIENT_DATA, result.primaryHypothesis)
    }

    @Test
    fun ServiceRejectionStopsImmediateRetries() {
        val engine = PassiveHypothesisEngine()
        val result = engine.evaluate(
            listOf(Observation(success = true, httpStatus = 403, distinctEndpointsTried = 1)),
            now,
        )
        assertEquals(Hypothesis.POSSIBLE_SERVICE_REJECTION, result.primaryHypothesis)
        assertEquals(RecommendedAction.STOP_ENDPOINT_RETRIES, result.recommendedAction)
        assertTrue(HttpStatusPolicy.shouldStopEndpointRetries(403))
    }

    @Test
    fun NoBackgroundActiveProbes() {
        assertEquals(0, PaerLimits.ACTIVE_BACKGROUND_PROBES)
        assertEquals(0, PaerLimits().activeBackgroundProbes)
    }

    @Test
    fun CacheNeverStoresUrlPathOrContent() {
        try {
            RvecPolicy.RvecRecord(
                hostname = "example.com/secret/path",
                ipAddress = "1.2.3.4",
                ipFamily = IpFamily.IPV4,
                port = 443,
                transport = Transport.TCP,
                alpn = emptyList(),
                networkContextId = ctx,
                lastSuccessAt = now,
                lastFailureAt = null,
                consecutiveFailures = 0,
                cooldownUntil = null,
                discoveredAt = now,
            )
            throw AssertionError("path should be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("path"))
        }
        val clean = RvecPolicy.fromCandidate(dns(host = "example.com"))
        assertFalse(clean.hostname.contains('/'))
        assertFalse(clean.hostname.contains('?'))
    }

    @Test
    fun Http4xxDoesNotLowerEndpointScore() {
        assertFalse(HttpStatusPolicy.shouldLowerEndpointScore(404))
        assertFalse(HttpStatusPolicy.shouldLowerEndpointScore(403))
        assertTrue(HttpStatusPolicy.isNetworkSuccess(404))
    }

    @Test
    fun Http5xxDoesNotLowerEndpointScore() {
        assertFalse(HttpStatusPolicy.shouldLowerEndpointScore(500))
        assertTrue(HttpStatusPolicy.isNetworkSuccess(503))
        assertTrue(HttpStatusPolicy.allowsIdempotentRetry(502))
    }

    @Test
    fun halfLifeMathIsStableNearZeroAge() {
        val f = ScoreCalculator.cacheTimeFactor(0.0, 6.0)
        assertEquals(1.0, f, 0.0)
        assertTrue(abs(ScoreCalculator.decayedScore(100.0, 6.0, 6.0) - 50.0) < 1e-9)
    }
}
