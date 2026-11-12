package com.hubshark.client.ui

import com.hubshark.client.model.AppConnectionState
import com.hubshark.client.model.RouteState
import com.hubshark.client.model.TunnelState
import com.hubshark.client.model.Transport
import com.hubshark.client.network.VpnBypassSocketFactory
import com.hubshark.client.vpn.XrayDelaySummary
import com.hubshark.client.vpn.RoutePerformanceSummary
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
class HubSharkViewModelTest {

    @Test
    fun normalizeWorkerUrl_validFormats_correct() {
        assertEquals("https://example.com/wake", HubSharkViewModel.normalizeWorkerUrlVisibleForTesting("example.com", HubSharkViewModel.Companion.WorkerEndpoint.WAKE))
        assertEquals("https://example.com/api/health", HubSharkViewModel.normalizeWorkerUrlVisibleForTesting("https://example.com/", HubSharkViewModel.Companion.WorkerEndpoint.HEALTH))
        assertEquals("https://example.com/wake", HubSharkViewModel.normalizeWorkerUrlVisibleForTesting("https://example.com/wake", HubSharkViewModel.Companion.WorkerEndpoint.WAKE))
        assertEquals("https://example.com/api/health", HubSharkViewModel.normalizeWorkerUrlVisibleForTesting("https://example.com/api/health", HubSharkViewModel.Companion.WorkerEndpoint.HEALTH))
        assertEquals("https://example.com/wake", HubSharkViewModel.normalizeWorkerUrlVisibleForTesting("https://example.com/api/wake", HubSharkViewModel.Companion.WorkerEndpoint.WAKE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizeWorkerUrl_http_fails() {
        HubSharkViewModel.normalizeWorkerUrlVisibleForTesting("http://example.com", HubSharkViewModel.Companion.WorkerEndpoint.WAKE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizeWorkerUrl_randomPath_fails() {
        HubSharkViewModel.normalizeWorkerUrlVisibleForTesting("https://example.com/random/path", HubSharkViewModel.Companion.WorkerEndpoint.WAKE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizeWorkerUrl_whitespace_fails() {
        HubSharkViewModel.normalizeWorkerUrlVisibleForTesting("https://example .com", HubSharkViewModel.Companion.WorkerEndpoint.WAKE)
    }

    @Test
    fun workerHttpClient_readTimeoutCoversAWorkerProgressResponse() {
        val client = HubSharkViewModel.buildWorkerHttpClientVisibleForTesting(loggingEnabled = false)

        assertEquals(10_000, client.connectTimeoutMillis)
        assertTrue("read timeout must cover Worker progress response", client.readTimeoutMillis >= 40_000)
        assertTrue(
            "UI Worker calls must bypass an active VPN through a protected socket",
            client.socketFactory is VpnBypassSocketFactory
        )
        assertNotSame(
            "UI Worker DNS must resolve outside a dead VPN before the protected socket is created",
            Dns.SYSTEM,
            client.dns
        )
    }

    @Test
    fun workerWakeResponsePollability_matchesWorkerSettlingStates() {
        assertTrue(
            HubSharkViewModel.shouldPollWorkerWakeResponseVisibleForTesting(
                httpCode = 202,
                body = com.hubshark.client.network.WorkerWakeResponse(
                    ok = false,
                    start_accepted = true,
                    route_ready = null,
                    next_action_code = "wait_codespace_available"
                )
            )
        )
        assertTrue(
            HubSharkViewModel.shouldPollWorkerWakeResponseVisibleForTesting(
                httpCode = 200,
                body = com.hubshark.client.network.WorkerWakeResponse(
                    ok = true,
                    route_ready = false,
                    next_action_code = "wait_route_or_recover"
                )
            )
        )
        assertFalse(
            HubSharkViewModel.shouldPollWorkerWakeResponseVisibleForTesting(
                httpCode = 402,
                body = com.hubshark.client.network.WorkerWakeResponse(
                    ok = false,
                    quota_blocked = true,
                    route_ready = false
                )
            )
        )
    }

    @Test
    fun wakeRecentlySucceededError_switchesToHealthPolling() {
        assertTrue(
            HubSharkViewModel.shouldCheckHealthAfterWakeErrorVisibleForTesting(
                httpCode = 429,
                body = com.hubshark.client.network.WorkerWakeResponse(
                    ok = false,
                    reason = "wake_recently_succeeded",
                    retry_after_seconds = 60
                )
            )
        )
        assertFalse(
            HubSharkViewModel.shouldCheckHealthAfterWakeErrorVisibleForTesting(
                httpCode = 401,
                body = com.hubshark.client.network.WorkerWakeResponse(
                    ok = false,
                    error = "unauthorized"
                )
            )
        )
    }

    @Test
    fun routeStateUpdate_preservesRunningTunnelStateAndSpeeds() {
        val previous = AppConnectionState(
            routeState = RouteState.Ready,
            tunnelState = TunnelState.Connected,
            message = "VPN connected",
            activeAccountId = "old-account",
            activeProfileId = "profile-1",
            routeReady = true,
            tunnelVerified = true,
            downloadBps = 1234,
            uploadBps = 567,
            totalRxBytes = 9000,
            totalTxBytes = 8000
        )

        val updated = HubSharkViewModel.mergeRouteStateVisibleForTesting(
            previous = previous,
            routeState = RouteState.Settling,
            message = "Codespace route settling",
            activeAccountId = "account-2",
            activeProfileId = null,
            routeReady = false
        )

        assertEquals(TunnelState.Connected, updated.tunnelState)
        assertEquals(1234, updated.downloadBps)
        assertEquals(567, updated.uploadBps)
        assertEquals(9000, updated.totalRxBytes)
        assertEquals(8000, updated.totalTxBytes)
        assertEquals("profile-1", updated.activeProfileId)
        assertEquals("account-2", updated.activeAccountId)
        assertFalse(updated.routeReady)
        assertTrue(updated.tunnelVerified)
    }

    @Test
    fun vpnStartAllowed_blocksDuplicateRunningStates() {
        assertFalse(HubSharkViewModel.vpnStartAllowedVisibleForTesting(TunnelState.Starting))
        assertFalse(HubSharkViewModel.vpnStartAllowedVisibleForTesting(TunnelState.Connected))
        assertFalse(HubSharkViewModel.vpnStartAllowedVisibleForTesting(TunnelState.Stopping))
        assertTrue(HubSharkViewModel.vpnStartAllowedVisibleForTesting(TunnelState.Disabled))
        assertTrue(HubSharkViewModel.vpnStartAllowedVisibleForTesting(TunnelState.Disconnected))
        assertTrue(HubSharkViewModel.vpnStartAllowedVisibleForTesting(TunnelState.Error))
    }

    @Test
    fun vpnStartRequestBusy_blocksASecondRequestBeforeItCanReplaceThePendingProfile() {
        assertTrue(HubSharkViewModel.vpnStartRequestBusyVisibleForTesting(routeProbeActive = true))
        assertFalse(HubSharkViewModel.vpnStartRequestBusyVisibleForTesting(routeProbeActive = false))
    }

    @Test
    fun configurationMutations_areBlockedDuringTunnelLifecycleTransitions() {
        assertFalse(HubSharkViewModel.configurationMutationAllowedVisibleForTesting(TunnelState.Starting))
        assertFalse(HubSharkViewModel.configurationMutationAllowedVisibleForTesting(TunnelState.Connected))
        assertFalse(HubSharkViewModel.configurationMutationAllowedVisibleForTesting(TunnelState.Stopping))
        assertFalse(
            HubSharkViewModel.configurationMutationAllowedVisibleForTesting(
                TunnelState.Disconnected,
                startupBusy = true
            )
        )
        assertTrue(HubSharkViewModel.configurationMutationAllowedVisibleForTesting(TunnelState.Disconnected))
        assertTrue(HubSharkViewModel.configurationMutationAllowedVisibleForTesting(TunnelState.Error))
    }

    @Test
    fun vpnStartPlan_checksTheActiveRouteBeforeWakingWheneverWorkerIsConfigured() {
        assertEquals(
            HubSharkViewModel.VpnStartPlan.DirectProbeThenWake,
            HubSharkViewModel.vpnStartPlanVisibleForTesting(workerConfigured = true, routeReady = true)
        )
        assertEquals(
            HubSharkViewModel.VpnStartPlan.DirectProbeThenWake,
            HubSharkViewModel.vpnStartPlanVisibleForTesting(workerConfigured = true, routeReady = false)
        )
        assertEquals(
            HubSharkViewModel.VpnStartPlan.StartDirect,
            HubSharkViewModel.vpnStartPlanVisibleForTesting(workerConfigured = false, routeReady = true)
        )
        assertEquals(
            HubSharkViewModel.VpnStartPlan.StartDirect,
            HubSharkViewModel.vpnStartPlanVisibleForTesting(workerConfigured = false, routeReady = false)
        )
    }

    @Test
    fun workerNetworkFailure_fallsBackOnlyForAPendingVpnStart() {
        assertEquals(
            HubSharkViewModel.WorkerNetworkFailureAction.DirectVpnFallback,
            HubSharkViewModel.workerNetworkFailureActionVisibleForTesting(
                pendingVpnStart = true,
                throwable = SocketTimeoutException("timed out")
            )
        )
        assertEquals(
            HubSharkViewModel.WorkerNetworkFailureAction.ReportFailure,
            HubSharkViewModel.workerNetworkFailureActionVisibleForTesting(
                pendingVpnStart = false,
                throwable = SocketTimeoutException("timed out")
            )
        )
        assertEquals(
            HubSharkViewModel.WorkerNetworkFailureAction.ReportFailure,
            HubSharkViewModel.workerNetworkFailureActionVisibleForTesting(
                pendingVpnStart = true,
                throwable = IllegalArgumentException("bad URL")
            )
        )
    }

    @Test
    fun workerTransientHttpFailure_triesTheActiveRouteButNeverMasksQuotaOrAuth() {
        assertEquals(
            HubSharkViewModel.WorkerNetworkFailureAction.DirectVpnFallback,
            HubSharkViewModel.workerHttpFailureActionVisibleForTesting(
                pendingVpnStart = true,
                httpCode = 503,
                body = com.hubshark.client.network.WorkerWakeResponse(ok = false)
            )
        )
        assertEquals(
            HubSharkViewModel.WorkerNetworkFailureAction.ReportFailure,
            HubSharkViewModel.workerHttpFailureActionVisibleForTesting(
                pendingVpnStart = true,
                httpCode = 402,
                body = com.hubshark.client.network.WorkerWakeResponse(ok = false, quota_blocked = true)
            )
        )
        assertEquals(
            HubSharkViewModel.WorkerNetworkFailureAction.ReportFailure,
            HubSharkViewModel.workerHttpFailureActionVisibleForTesting(
                pendingVpnStart = true,
                httpCode = 401,
                body = com.hubshark.client.network.WorkerWakeResponse(ok = false)
            )
        )
        assertEquals(
            HubSharkViewModel.WorkerNetworkFailureAction.ReportFailure,
            HubSharkViewModel.workerHttpFailureActionVisibleForTesting(
                pendingVpnStart = false,
                httpCode = 503,
                body = com.hubshark.client.network.WorkerWakeResponse(ok = false)
            )
        )
    }

    @Test
    fun delayScanCompletion_reportsPersistenceFailuresHonestly() {
        assertEquals(
            "Quick delay scan complete for 5 configs. Use the per-config test for a 3-sample measurement.",
            HubSharkViewModel.delayScanCompletionMessageVisibleForTesting(
                total = 5,
                persistenceFailures = 0
            )
        )
        assertEquals(
            "Quick delay scan measured 5 configs, but 2 result(s) could not be saved.",
            HubSharkViewModel.delayScanCompletionMessageVisibleForTesting(
                total = 5,
                persistenceFailures = 2
            )
        )
        assertEquals(
            "Ping all complete: 3/5 configs responded. Failed configs show their error.",
            HubSharkViewModel.delayScanCompletionMessageVisibleForTesting(
                total = 5,
                persistenceFailures = 0,
                successful = 3
            )
        )
        assertEquals(
            "Ping all finished, but none of 5 configs responded. Check route readiness or try a per-config test.",
            HubSharkViewModel.delayScanCompletionMessageVisibleForTesting(
                total = 5,
                persistenceFailures = 0,
                successful = 0
            )
        )
    }

    @Test
    fun interruptedDelayTests_areReleasedWithoutOverwritingCompletedResults() {
        val states = mapOf(
            "pending" to ProfileDelayState(testing = true),
            "done" to ProfileDelayState(testing = false, delayMs = 42L)
        )

        val cleared = HubSharkViewModel.clearInterruptedDelayStatesVisibleForTesting(
            states = states,
            ids = setOf("pending", "done"),
            message = "interrupted",
            testedAt = 123L
        )

        assertFalse(cleared.getValue("pending").testing)
        assertEquals("interrupted", cleared.getValue("pending").error)
        assertEquals(123L, cleared.getValue("pending").testedAt)
        assertFalse(cleared.getValue("done").testing)
        assertEquals(42L, cleared.getValue("done").delayMs)
        assertEquals(null, cleared.getValue("done").error)
    }

    @Test
    fun routeOptimizationScore_penalizesTailJitterRetriesAndDeprecatedWs() {
        val stable = XrayDelaySummary(true, 100L, 110L, 5L, 2, 2, null)
        val unstable = XrayDelaySummary(true, 100L, 300L, 80L, 1, 2, null)

        val stableXhttp = HubSharkViewModel.routeOptimizationScoreVisibleForTesting(stable, Transport.XHTTP)
        val stableWs = HubSharkViewModel.routeOptimizationScoreVisibleForTesting(stable, Transport.WS)
        val unstableXhttp = HubSharkViewModel.routeOptimizationScoreVisibleForTesting(unstable, Transport.XHTTP)

        assertTrue(stableXhttp < stableWs)
        assertTrue(stableWs < unstableXhttp)
    }

    @Test
    fun routeOptimizationScore_prefersUsefulThroughputOverAFragilePingOnlyWinner() {
        val slightlyFasterPing = XrayDelaySummary(true, 80L, 95L, 8L, 3, 3, null)
        val stableFastTransfer = XrayDelaySummary(true, 110L, 125L, 8L, 3, 3, null)

        val slowRoute = HubSharkViewModel.routeOptimizationScoreVisibleForTesting(
            slightlyFasterPing,
            Transport.XHTTP,
            RoutePerformanceSummary(true, downloadBps = 180_000L, uploadBps = 80_000L)
        )
        val fastRoute = HubSharkViewModel.routeOptimizationScoreVisibleForTesting(
            stableFastTransfer,
            Transport.XHTTP,
            RoutePerformanceSummary(true, downloadBps = 4_000_000L, uploadBps = 1_000_000L)
        )

        assertTrue(fastRoute < slowRoute)
    }

    @Test
    fun routeOptimizationComparison_doesNotRewardUnknownThroughputWhenMeasurementsExist() {
        val unknownButLowerPing = XrayDelaySummary(true, 75L, 90L, 5L, 3, 3, null)
        val measuredRoute = XrayDelaySummary(true, 110L, 125L, 5L, 3, 3, null)

        val unknownScore = HubSharkViewModel.routeOptimizationComparisonScoreVisibleForTesting(
            unknownButLowerPing,
            Transport.XHTTP,
            performance = null,
            hasPerformanceEvidence = true
        )
        val measuredScore = HubSharkViewModel.routeOptimizationComparisonScoreVisibleForTesting(
            measuredRoute,
            Transport.XHTTP,
            RoutePerformanceSummary(true, downloadBps = 4_000_000L, uploadBps = 1_000_000L),
            hasPerformanceEvidence = true
        )

        assertTrue(measuredScore < unknownScore)
    }

    @Test
    fun autoRouteOptimization_allowsOneAttemptAfterANewAvailableWake() {
        val now = 2_000_000L
        fun allowed(lastAttemptAt: Long) = HubSharkViewModel.shouldAutoOptimizeRoutesVisibleForTesting(
            enabled = true,
            lastAttemptAt = lastAttemptAt,
            now = now,
            routeWasReady = true,
            profileCount = 3,
            tunnelState = TunnelState.Disconnected,
            busy = false,
            availableWakeAt = now - 1_000L
        )

        assertTrue(allowed(now - 10_000L))
        assertFalse(allowed(now))
    }

    @Test
    fun autoRouteOptimization_oldAndFutureWakesDoNotBypassCooldown() {
        val now = 2_000_000L
        listOf(now - 900_001L, now - 300_001L, now + 1L).forEach { wakeAt ->
            assertFalse(
                HubSharkViewModel.shouldAutoOptimizeRoutesVisibleForTesting(
                    enabled = true,
                    lastAttemptAt = now - 900_000L,
                    now = now,
                    routeWasReady = true,
                    profileCount = 3,
                    tunnelState = TunnelState.Disconnected,
                    busy = false,
                    availableWakeAt = wakeAt
                )
            )
        }
    }

    @Test
    fun autoRouteOptimization_newWakeStillHonorsSessionAndBusyGuards() {
        val now = 2_000_000L
        fun allowed(
            state: TunnelState = TunnelState.Disconnected,
            busy: Boolean = false,
            enabled: Boolean = true,
            ready: Boolean = true,
            profiles: Int = 3
        ) = HubSharkViewModel.shouldAutoOptimizeRoutesVisibleForTesting(
            enabled = enabled,
            lastAttemptAt = now - 10_000L,
            now = now,
            routeWasReady = ready,
            profileCount = profiles,
            tunnelState = state,
            busy = busy,
            availableWakeAt = now - 1_000L
        )

        assertFalse(allowed(state = TunnelState.Connected))
        assertFalse(allowed(state = TunnelState.Starting))
        assertFalse(allowed(busy = true))
        assertFalse(allowed(enabled = false))
        assertFalse(allowed(ready = false))
        assertFalse(allowed(profiles = 0))
    }

    @Test
    fun autoRouteOptimization_requiresReadyStoppedRouteAndCooldown() {
        val now = 2_000_000L
        assertTrue(
            HubSharkViewModel.shouldAutoOptimizeRoutesVisibleForTesting(
                enabled = true,
                lastAttemptAt = 0L,
                now = now,
                routeWasReady = true,
                profileCount = 3,
                tunnelState = TunnelState.Disconnected,
                busy = false
            )
        )
        assertFalse(
            HubSharkViewModel.shouldAutoOptimizeRoutesVisibleForTesting(
                enabled = true,
                lastAttemptAt = now - 1_000L,
                now = now,
                routeWasReady = true,
                profileCount = 3,
                tunnelState = TunnelState.Disconnected,
                busy = false
            )
        )
        assertFalse(
            HubSharkViewModel.shouldAutoOptimizeRoutesVisibleForTesting(
                enabled = true,
                lastAttemptAt = 0L,
                now = now,
                routeWasReady = false,
                profileCount = 3,
                tunnelState = TunnelState.Disconnected,
                busy = false
            )
        )
        assertFalse(
            HubSharkViewModel.shouldAutoOptimizeRoutesVisibleForTesting(
                enabled = true,
                lastAttemptAt = 0L,
                now = now,
                routeWasReady = true,
                profileCount = 3,
                tunnelState = TunnelState.Connected,
                busy = false
            )
        )
    }
}
