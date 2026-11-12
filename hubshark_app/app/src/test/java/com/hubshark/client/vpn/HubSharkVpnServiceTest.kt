package com.hubshark.client.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hubshark.client.model.ActiveVpnEngine
import com.hubshark.client.model.HubSharkProfile
import com.hubshark.client.model.StoredHubSharkProfile
import com.hubshark.client.model.Transport
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HubSharkVpnServiceTest {
    @Test
    fun repeatedProbeFailuresEventuallyRecoverEvenWithBackgroundPayload() {
        assertFalse(HubSharkVpnService.routeHealthFailureRequiresRecovery(2, false))
        assertTrue(HubSharkVpnService.routeHealthFailureRequiresRecovery(3, false))
        assertFalse(HubSharkVpnService.routeHealthFailureRequiresRecovery(3, true))
        assertTrue(HubSharkVpnService.routeHealthFailureRequiresRecovery(6, true))
    }

    @Test
    fun optionalEndpointDnsFailureDoesNotBreakSelectedRoute() {
        val selected = liveProfile("203.0.113.1")
        val backup = liveProfile("backup.example")
        val workingBackup = liveProfile("203.0.113.2")
        val result = HubSharkVpnService.resolveRuntimeProfiles(listOf(selected, backup, workingBackup)) {
            if (it == backup.address) throw java.net.UnknownHostException("DNS unavailable")
            it
        }
        assertEquals(listOf(selected, workingBackup), result)
    }

    @Test(expected = java.net.UnknownHostException::class)
    fun selectedEndpointDnsFailureStillReportsFailure() {
        HubSharkVpnService.resolveRuntimeProfiles(listOf(liveProfile("selected.example"))) {
            throw java.net.UnknownHostException("Selected endpoint unavailable")
        }
    }

    @Test
    fun resolvingAnAliasDoesNotDuplicateTheSameLiveEndpoint() {
        val selected = liveProfile("203.0.113.1")
        val result = HubSharkVpnService.resolveRuntimeProfiles(listOf(selected, liveProfile("alias.example"))) {
            "203.0.113.1"
        }
        assertEquals(listOf(selected), result)
    }

    @Test(expected = kotlinx.coroutines.CancellationException::class)
    fun backupEndpointResolutionPreservesCancellation() {
        HubSharkVpnService.resolveRuntimeProfiles(listOf(liveProfile("203.0.113.1"), liveProfile("backup.example"))) {
            if (it == "backup.example") throw kotlinx.coroutines.CancellationException("VPN stopped")
            it
        }
    }

    @Test
    fun routeHealthClassificationSeparatesPacketEngineXrayAndBindingFailures() {
        val fullFailure = RunningRouteResult(
            success = false,
            error = "full path timeout",
            failureScope = RouteFailureScope.PacketEngine
        )
        val xraySuccess = RunningRouteResult(success = true, latencyMs = 80L)
        val xrayFailure = RunningRouteResult(success = false, error = "SOCKS route timeout")

        val packetEngine = HubSharkVpnService.classifyVpnRouteHealth(fullFailure, xraySuccess)
        assertEquals(RouteFailureScope.PacketEngine, packetEngine.failureScope)
        assertTrue(packetEngine.error.orEmpty().contains("Xray/Codespaces route is reachable"))

        val xrayRoute = HubSharkVpnService.classifyVpnRouteHealth(fullFailure, xrayFailure)
        assertEquals(RouteFailureScope.XrayRoute, xrayRoute.failureScope)
        assertTrue(xrayRoute.error.orEmpty().contains("SOCKS route timeout"))

        val successfulRouteIgnoresStaleBindingFailure = HubSharkVpnService.classifyVpnRouteHealth(
            fullPath = RunningRouteResult(success = true),
            xrayRoute = xraySuccess,
            dialerFailure = "selected network bind failed"
        )
        assertTrue(successfulRouteIgnoresStaleBindingFailure.success)
        assertEquals(RouteFailureScope.None, successfulRouteIgnoresStaleBindingFailure.failureScope)

        val binding = HubSharkVpnService.classifyVpnRouteHealth(
            fullPath = fullFailure,
            xrayRoute = xrayFailure,
            dialerFailure = "selected network bind failed"
        )
        assertEquals(RouteFailureScope.DialerBinding, binding.failureScope)
        assertEquals("selected network bind failed", binding.error)
    }

    private fun storedProfile(
        id: String,
        address: String,
        delayMs: Long,
        rawUri: String = "vless://$id@$address:443"
    ): StoredHubSharkProfile {
        return StoredHubSharkProfile(
            id = id,
            accountId = "account",
            displayName = id,
            rawUri = rawUri,
            transport = Transport.XHTTP,
            address = address,
            port = 443,
            sni = "space-443.app.github.dev",
            hostHeader = "space-443.app.github.dev",
            path = "/",
            isDefault = false,
            createdAt = delayMs,
            updatedAt = delayMs,
            lastDelayMs = delayMs
        )
    }

    @Test
    fun startRequestDuringCleanupIsQueued() {
        assertTrue(HubSharkVpnService.shouldQueueStartRequest(cleanupActive = true))
        assertFalse(HubSharkVpnService.shouldQueueStartRequest(cleanupActive = false))
    }

    @Test
    fun tunnelEndpointResolverRecognizesOnlyValidNumericAddresses() {
        assertTrue(SocketProtector.isNumericAddress("20.125.70.28"))
        assertTrue(SocketProtector.isNumericAddress("2001:db8::1"))
        assertFalse(SocketProtector.isNumericAddress("999.125.70.28"))
        assertFalse(SocketProtector.isNumericAddress("codespace-443.app.github.dev"))
    }

    @Test
    fun underlyingNetworkSelectionRejectsVpnAndPrefersValidatedPhysicalNetwork() {
        assertEquals(
            Int.MIN_VALUE,
            HubSharkVpnService.underlyingNetworkPreferenceScore(
                hasInternet = true,
                isNotVpn = false,
                isValidated = true,
                isNotMetered = true,
                transportScore = 40
            )
        )
        assertTrue(
            HubSharkVpnService.underlyingNetworkPreferenceScore(
                hasInternet = true,
                isNotVpn = true,
                isValidated = true,
                isNotMetered = false,
                transportScore = 20
            ) >
                HubSharkVpnService.underlyingNetworkPreferenceScore(
                    hasInternet = true,
                    isNotVpn = true,
                    isValidated = false,
                    isNotMetered = true,
                    transportScore = 40
                )
        )
    }

    @Test
    fun underlyingNetworkSnapshotMergePreservesCallbacksAndSkipsLostNetworks() {
        val merged = HubSharkVpnService.mergeUnderlyingNetworkCandidates(
            callbackObserved = mapOf(
                "wifi" to "callback-current"
            ),
            callbackLost = setOf("stale-cellular"),
            snapshot = mapOf(
                "wifi" to "older-snapshot",
                "stale-cellular" to "stale-snapshot",
                "ethernet" to "snapshot-current"
            )
        )

        assertEquals("callback-current", merged["wifi"])
        assertEquals("snapshot-current", merged["ethernet"])
        assertFalse(merged.containsKey("stale-cellular"))
    }

    @Test
    fun localSocksConnectionRefusalTriggersImmediateRecovery() {
        assertTrue(
            HubSharkVpnService.shouldRecoverImmediatelyForLocalSocksFailure(
                "failed to connect to /127.0.0.1 (port 45277): isConnected failed: ECONNREFUSED"
            )
        )
    }

    @Test
    fun remoteOrGenericFailuresStillRequireTheNormalTwoProbeThreshold() {
        assertFalse(HubSharkVpnService.shouldRecoverImmediatelyForLocalSocksFailure("upstream timeout"))
        assertFalse(
            HubSharkVpnService.shouldRecoverImmediatelyForLocalSocksFailure(
                "failed to connect to 203.0.113.1:443: ECONNREFUSED"
            )
        )
    }

    @Test
    fun transitionFailuresAreConfirmedBeforeDisruptiveRecovery() {
        assertTrue(
            HubSharkVpnService.shouldConfirmTransitionRouteFailure(
                RunningRouteResult(false, "upstream timeout")
            )
        )
        assertFalse(
            HubSharkVpnService.shouldConfirmTransitionRouteFailure(
                RunningRouteResult(
                    false,
                    "failed to connect to /127.0.0.1:12345: ECONNREFUSED"
                )
            )
        )
        assertFalse(
            HubSharkVpnService.shouldConfirmTransitionRouteFailure(
                RunningRouteResult(true)
            )
        )
    }

    @Test
    fun failedHealthChecksUseFastConfirmationsWithoutIncreasingNormalProbeTraffic() {
        assertEquals(
            5_000L,
            HubSharkVpnService.routeHealthCheckDelayMs(
                routeSuccess = false,
                trafficIsAdvancing = false,
                routeFailureCount = 1
            )
        )
        assertEquals(
            30_000L,
            HubSharkVpnService.routeHealthCheckDelayMs(
                routeSuccess = true,
                trafficIsAdvancing = false,
                routeFailureCount = 0
            )
        )
        assertEquals(
            30_000L,
            HubSharkVpnService.routeHealthCheckDelayMs(
                routeSuccess = false,
                trafficIsAdvancing = true,
                routeFailureCount = 1
            )
        )
    }

    @Test
    fun lifecycleGuardInvalidatesStaleStartOrRecoveryWork() {
        val guard = TunnelLifecycleGuard()
        val first = guard.begin()

        assertTrue(guard.isCurrent(first))
        guard.invalidate()
        assertFalse(guard.isCurrent(first))

        val second = guard.begin()
        assertTrue(guard.isCurrent(second))
        assertFalse(guard.isCurrent(first))
    }

    @Test
    fun periodicHealthChecksPauseWhileAndroidHasNoUnderlyingNetwork() {
        assertTrue(HubSharkVpnService.shouldPauseRouteHealthChecks(waitingForUnderlyingNetwork = true))
        assertFalse(HubSharkVpnService.shouldPauseRouteHealthChecks(waitingForUnderlyingNetwork = false))
    }

    @Test
    fun networkReturnRecoversAStoppedCoreInsteadOfSilentlySkippingIt() {
        assertEquals(
            NetworkHandoffAction.RecoverCore,
            HubSharkVpnService.networkHandoffAction(
                coroutineActive = true,
                hasVpnInterface = true,
                coreState = TunnelCoreState.Error
            )
        )
        assertEquals(
            NetworkHandoffAction.VerifyRoute,
            HubSharkVpnService.networkHandoffAction(
                coroutineActive = true,
                hasVpnInterface = true,
                coreState = TunnelCoreState.Connected
            )
        )
        assertEquals(
            NetworkHandoffAction.Defer,
            HubSharkVpnService.networkHandoffAction(
                coroutineActive = true,
                hasVpnInterface = true,
                coreState = TunnelCoreState.Starting
            )
        )
        assertEquals(
            NetworkHandoffAction.Ignore,
            HubSharkVpnService.networkHandoffAction(
                coroutineActive = false,
                hasVpnInterface = true,
                coreState = TunnelCoreState.Connected
            )
        )
    }

    @Test
    fun recoveryRetriesOnlyWhenTheUnderlyingNetworkChanged() {
        assertTrue(
            HubSharkVpnService.shouldRetryAfterNetworkChange(
                recoveryNetworkGeneration = 4,
                currentNetworkGeneration = 5,
                waitingForUnderlyingNetwork = false
            )
        )
        assertTrue(
            HubSharkVpnService.shouldRetryAfterNetworkChange(
                recoveryNetworkGeneration = 5,
                currentNetworkGeneration = 5,
                waitingForUnderlyingNetwork = true
            )
        )
        assertFalse(
            HubSharkVpnService.shouldRetryAfterNetworkChange(
                recoveryNetworkGeneration = 5,
                currentNetworkGeneration = 5,
                waitingForUnderlyingNetwork = false
            )
        )
    }

    @Test
    fun repeatedAppResumeDoesNotQueueOverlappingRouteReverification() {
        assertTrue(
            HubSharkVpnService.shouldStartRouteReverification(
                routeRecoveryInProgress = false,
                routeRecoveryActive = false,
                routeReverifyActive = false,
                networkHandoffActive = false,
                startActive = false,
                cleanupActive = false
            )
        )
        assertFalse(
            HubSharkVpnService.shouldStartRouteReverification(
                routeRecoveryInProgress = false,
                routeRecoveryActive = false,
                routeReverifyActive = true,
                networkHandoffActive = false,
                startActive = false,
                cleanupActive = false
            )
        )
        assertFalse(
            HubSharkVpnService.shouldStartRouteReverification(
                routeRecoveryInProgress = true,
                routeRecoveryActive = false,
                routeReverifyActive = false,
                networkHandoffActive = false,
                startActive = false,
                cleanupActive = false
            )
        )
        assertFalse(
            HubSharkVpnService.shouldStartRouteReverification(
                routeRecoveryInProgress = false,
                routeRecoveryActive = false,
                routeReverifyActive = false,
                networkHandoffActive = true,
                startActive = false,
                cleanupActive = false
            )
        )
    }

    @Test
    fun startupNetworkChangeRequiresTransitionAwareRouteConfirmation() {
        assertTrue(
            HubSharkVpnService.shouldReverifyAfterStartupNetworkChange(
                routeSuccess = true,
                startupNetworkGeneration = 7,
                currentNetworkGeneration = 8
            )
        )
        assertFalse(
            HubSharkVpnService.shouldReverifyAfterStartupNetworkChange(
                routeSuccess = true,
                startupNetworkGeneration = 7,
                currentNetworkGeneration = 7
            )
        )
        assertFalse(
            HubSharkVpnService.shouldReverifyAfterStartupNetworkChange(
                routeSuccess = false,
                startupNetworkGeneration = 7,
                currentNetworkGeneration = 8
            )
        )
    }

    @Test
    fun appResumeRecoversEveryPersistedTunnelStateWithoutStoppingTheStickyService() {
        assertEquals(
            AppResumeAction.VerifyRoute,
            HubSharkVpnService.appResumeAction(
                coreState = TunnelCoreState.Connected,
                hasVpnInterface = true,
                hasActiveProfile = true,
                cleanupActive = false
            )
        )
        assertEquals(
            AppResumeAction.KeepAlive,
            HubSharkVpnService.appResumeAction(
                coreState = TunnelCoreState.Starting,
                hasVpnInterface = true,
                hasActiveProfile = true,
                cleanupActive = false
            )
        )
        assertEquals(
            AppResumeAction.RecoverCore,
            HubSharkVpnService.appResumeAction(
                coreState = TunnelCoreState.Error,
                hasVpnInterface = true,
                hasActiveProfile = true,
                cleanupActive = false
            )
        )
        assertEquals(
            AppResumeAction.RestartTunnel,
            HubSharkVpnService.appResumeAction(
                coreState = TunnelCoreState.Disconnected,
                hasVpnInterface = false,
                hasActiveProfile = false,
                cleanupActive = false
            )
        )
        assertEquals(
            AppResumeAction.QueueRestart,
            HubSharkVpnService.appResumeAction(
                coreState = TunnelCoreState.Connected,
                hasVpnInterface = true,
                hasActiveProfile = true,
                cleanupActive = true
            )
        )
        assertEquals(
            AppResumeAction.QueueRestart,
            HubSharkVpnService.appResumeAction(
                coreState = TunnelCoreState.Stopping,
                hasVpnInterface = true,
                hasActiveProfile = true,
                cleanupActive = false
            )
        )
    }

    @Test
    fun platformChoosesTheBestPhysicalNetworkOnAndroid12AndNewer() {
        assertFalse(HubSharkVpnService.shouldUsePlatformBestUnderlyingNetwork(30))
        assertTrue(HubSharkVpnService.shouldUsePlatformBestUnderlyingNetwork(31))
        assertTrue(HubSharkVpnService.shouldUsePlatformBestUnderlyingNetwork(36))
    }

    @Test
    fun backupSelectionTriesDifferentEdgeAddressesBeforeTransportVariants() {
        val currentRawUri = "vless://current@20.0.0.9:443"
        val selected = HubSharkVpnService.selectAlternativeProfiles(
            profiles = listOf(
                storedProfile("current", "20.0.0.9", 1, currentRawUri),
                storedProfile("edge-a-xhttp", "20.0.0.1", 10),
                storedProfile("edge-a-ws-h2", "20.0.0.1", 11),
                storedProfile("edge-a-ws-h1", "20.0.0.1", 12),
                storedProfile("edge-b", "20.0.0.2", 20),
                storedProfile("edge-c", "20.0.0.3", 30)
            ),
            accountId = "account",
            currentRawUri = currentRawUri,
            maxCandidates = 4
        )

        assertEquals(
            listOf("20.0.0.1", "20.0.0.2", "20.0.0.3"),
            selected.take(3).map { it.address }
        )
        assertEquals(4, selected.size)
        assertFalse(selected.any { it.rawUri == currentRawUri })
    }

    @Test
    fun preWakeBackupSelectionIsBoundedToOneMeasuredRoute() {
        val selected = HubSharkVpnService.selectAlternativeProfiles(
            profiles = listOf(
                storedProfile("edge-a", "20.0.0.1", 10),
                storedProfile("edge-b", "20.0.0.2", 20)
            ),
            accountId = "account",
            currentRawUri = "vless://current@20.0.0.9:443",
            maxCandidates = 1
        )

        assertEquals(listOf("20.0.0.1"), selected.map { it.address })
    }

    @Test
    fun liveSessionFailureMovesAStaleFastRouteBehindAnUntriedRoute() {
        val staleFast = storedProfile("stale-fast", "20.0.0.1", 10)
        val untried = storedProfile("untried", "20.0.0.2", 80)

        val selected = HubSharkVpnService.selectAlternativeProfiles(
            profiles = listOf(staleFast, untried),
            accountId = "account",
            currentRawUri = "vless://current@20.0.0.9:443",
            maxCandidates = 2,
            sessionHealth = mapOf(
                staleFast.rawUri to SessionProfileHealth(
                    consecutiveFailures = 1,
                    observedAtMs = 100L
                )
            )
        )

        assertEquals(listOf("untried", "stale-fast"), selected.map { it.id })
    }

    @Test
    fun liveSessionSuccessOverridesAnOldPersistedFailure() {
        val recovered = storedProfile("recovered", "20.0.0.1", 500).copy(
            lastDelayMs = null,
            lastDelayError = "old timeout",
            delayFailureCount = 4
        )
        val previouslyFast = storedProfile("previously-fast", "20.0.0.2", 20)

        val selected = HubSharkVpnService.selectAlternativeProfiles(
            profiles = listOf(previouslyFast, recovered),
            accountId = "account",
            currentRawUri = "vless://current@20.0.0.9:443",
            maxCandidates = 2,
            sessionHealth = mapOf(
                recovered.rawUri to SessionProfileHealth(
                    lastAttemptSucceeded = true,
                    lastSuccessLatencyMs = 45L,
                    observedAtMs = 200L
                )
            )
        )

        assertEquals(listOf("recovered", "previously-fast"), selected.map { it.id })
    }

    @Test
    fun backupSelection_penalizesPersistedTailLatencyAndJitter() {
        val stable = storedProfile("stable", "20.0.0.1", 100).copy(
            lastDelayP95Ms = 110L,
            lastDelayJitterMs = 5L
        )
        val unstable = storedProfile("unstable", "20.0.0.2", 100).copy(
            lastDelayP95Ms = 500L,
            lastDelayJitterMs = 100L
        )

        val selected = HubSharkVpnService.selectAlternativeProfiles(
            profiles = listOf(unstable, stable),
            accountId = "account",
            currentRawUri = "vless://current@20.0.0.9:443",
            maxCandidates = 2
        )

        assertEquals(listOf("stable", "unstable"), selected.map { it.id })
    }

    @Test
    fun sessionProfileHealthLearnsFailuresAndResetsAfterSuccess() {
        val firstFailure = HubSharkVpnService.updatedSessionProfileHealth(
            previous = null,
            success = false,
            latencyMs = null,
            observedAtMs = 100L
        )
        val secondFailure = HubSharkVpnService.updatedSessionProfileHealth(
            previous = firstFailure,
            success = false,
            latencyMs = null,
            observedAtMs = 200L
        )
        val recovered = HubSharkVpnService.updatedSessionProfileHealth(
            previous = secondFailure,
            success = true,
            latencyMs = 42L,
            observedAtMs = 300L
        )

        assertEquals(2, secondFailure.consecutiveFailures)
        assertEquals(null, secondFailure.lastSuccessLatencyMs)
        assertEquals(0, recovered.consecutiveFailures)
        assertEquals(42L, recovered.lastSuccessLatencyMs)
        assertEquals(300L, recovered.observedAtMs)
    }

    @Test
    fun staleSessionProfileHealthExpiresSoRecoveredRoutesCanBeTriedAgain() {
        val fresh = SessionProfileHealth(consecutiveFailures = 1, observedAtMs = 900L)
        val stale = SessionProfileHealth(consecutiveFailures = 2, observedAtMs = 100L)

        val observations = HubSharkVpnService.freshSessionProfileHealth(
            observations = mapOf("fresh" to fresh, "stale" to stale),
            nowMs = 1_000L,
            maxAgeMs = 500L
        )

        assertEquals(mapOf("fresh" to fresh), observations)
    }

    @Test
    fun onlyReceivedHevTrafficProvesTheRouteStillCarriesResponses() {
        val previous = TrafficBytes(rxBytes = 100L, txBytes = 200L, rxTcpPayloadBytes = 50L)

        assertTrue(
            HubSharkVpnService.measuredResponseTrafficAdvanced(
                ActiveVpnEngine.Hev,
                previous,
                TrafficBytes(rxBytes = 101L, txBytes = 200L, rxTcpPayloadBytes = 51L)
            )
        )
        assertFalse(
            HubSharkVpnService.measuredResponseTrafficAdvanced(
                ActiveVpnEngine.Hev,
                previous,
                TrafficBytes(rxBytes = 100L, txBytes = 201L, rxTcpPayloadBytes = 50L)
            )
        )
        assertFalse(
            HubSharkVpnService.measuredResponseTrafficAdvanced(
                ActiveVpnEngine.XrayNative,
                previous,
                TrafficBytes(rxBytes = 101L, txBytes = 200L, rxTcpPayloadBytes = 51L)
            )
        )
        assertFalse(
            HubSharkVpnService.measuredResponseTrafficAdvanced(
                ActiveVpnEngine.Hev,
                previous,
                null
            )
        )
    }

    @Test
    fun failedReverificationDoesNotRestartAHevTunnelWhoseResponsesResumed() {
        val previous = TrafficBytes(rxBytes = 100L, txBytes = 200L, rxTcpPayloadBytes = 50L)
        val resumed = TrafficBytes(rxBytes = 101L, txBytes = 220L, rxTcpPayloadBytes = 51L)

        assertTrue(
            HubSharkVpnService.shouldSuppressRouteRecoveryAfterFailedReverification(
                route = RunningRouteResult(
                    success = false,
                    error = "public probe timed out",
                    failureScope = RouteFailureScope.XrayRoute
                ),
                engine = ActiveVpnEngine.Hev,
                previousTraffic = previous,
                currentTraffic = resumed
            )
        )
        assertFalse(
            HubSharkVpnService.shouldSuppressRouteRecoveryAfterFailedReverification(
                route = RunningRouteResult(
                    success = false,
                    error = "dialer binding failed",
                    failureScope = RouteFailureScope.DialerBinding
                ),
                engine = ActiveVpnEngine.Hev,
                previousTraffic = previous,
                currentTraffic = resumed
            )
        )
        assertFalse(
            HubSharkVpnService.shouldSuppressRouteRecoveryAfterFailedReverification(
                route = RunningRouteResult(
                    success = false,
                    error = "ECONNREFUSED at 127.0.0.1",
                    failureScope = RouteFailureScope.XrayRoute
                ),
                engine = ActiveVpnEngine.Hev,
                previousTraffic = previous,
                currentTraffic = resumed
            )
        )
    }

    @Test
    fun localSynAckAndAckPacketsCannotProveUpstreamHealth() {
        val previous = TrafficBytes(0L, 0L, rxTcpPayloadBytes = 0L)
        val localSynAck = TrafficBytes(44L, 40L, 1L, 1L, rxTcpPayloadBytes = 0L)
        assertFalse(HubSharkVpnService.measuredResponseTrafficAdvanced(ActiveVpnEngine.Hev, previous, localSynAck))
        assertFalse(HubSharkVpnService.shouldSuppressRouteRecoveryAfterFailedReverification(
            RunningRouteResult(false, "route unavailable", failureScope = RouteFailureScope.XrayRoute),
            ActiveVpnEngine.Hev, previous, localSynAck
        ))
        assertFalse(HubSharkVpnService.measuredResponseTrafficAdvanced(
            ActiveVpnEngine.Hev, TrafficBytes(0L, 0L), TrafficBytes(44L, 40L)
        ))
        assertFalse(HubSharkVpnService.shouldSuppressRouteRecoveryAfterFailedReverification(
            RunningRouteResult(false, "TUN unavailable", failureScope = RouteFailureScope.PacketEngine),
            ActiveVpnEngine.Hev, previous, localSynAck.copy(rxTcpPayloadBytes = 100L)
        ))
    }

    @Test
    fun alternateTransportGetsAReservedSlotEvenWithSixUnmeasuredXhttpIps() {
        val uuid = "00000000-0000-0000-0000-000000000000"
        fun uri(address: String, transport: String) =
            "vless://$uuid@$address:443?encryption=none&security=tls&sni=example.com&host=example.com&type=$transport&path=%2F&mode=packet-up&alpn=h2"
        val profiles = (1..8).map {
            storedProfile("xhttp-$it", "20.0.0.$it", it.toLong(), uri("20.0.0.$it", "xhttp"))
        } + storedProfile("working-ws", "20.0.0.1", 99L, uri("20.0.0.1", "ws"))
        val selected = HubSharkVpnService.selectAlternativeProfiles(
            profiles, "account", uri("20.0.0.99", "xhttp"), maxCandidates = 6
        )
        assertEquals(6, selected.size)
        assertEquals("working-ws", selected[1].id)
    }

    @Test
    fun firstTrafficAfterIdleRequestsOnePromptRouteReverification() {
        assertTrue(
            HubSharkVpnService.shouldReverifyAfterIdleTraffic(
                engine = ActiveVpnEngine.Hev,
                idleDurationMs = 60_000L,
                rxBps = 0L,
                txBps = 1L,
                timeSinceLastReverifyMs = 30_000L
            )
        )
        assertFalse(
            HubSharkVpnService.shouldReverifyAfterIdleTraffic(
                engine = ActiveVpnEngine.Hev,
                idleDurationMs = 59_999L,
                rxBps = 0L,
                txBps = 1L,
                timeSinceLastReverifyMs = 30_000L
            )
        )
        assertFalse(
            HubSharkVpnService.shouldReverifyAfterIdleTraffic(
                engine = ActiveVpnEngine.Hev,
                idleDurationMs = 60_000L,
                rxBps = 0L,
                txBps = 0L,
                timeSinceLastReverifyMs = 30_000L
            )
        )
        assertFalse(
            HubSharkVpnService.shouldReverifyAfterIdleTraffic(
                engine = ActiveVpnEngine.Hev,
                idleDurationMs = 60_000L,
                rxBps = 0L,
                txBps = 1L,
                timeSinceLastReverifyMs = 29_999L
            )
        )
        assertFalse(
            HubSharkVpnService.shouldReverifyAfterIdleTraffic(
                engine = ActiveVpnEngine.XrayNative,
                idleDurationMs = 60_000L,
                rxBps = 0L,
                txBps = 1L,
                timeSinceLastReverifyMs = 30_000L
            )
        )
    }

    @Test
    fun sustainedHevTransmitWithoutResponsesRequestsAProbeButNeverInfersFailureDirectly() {
        assertFalse(
            HubSharkVpnService.shouldProbeSuspectedActiveStall(
                engine = ActiveVpnEngine.Hev,
                txBytesSinceLastRx = 32L * 1024L,
                txPacketsSinceLastRx = 0L,
                timeSinceLastRxMs = 5_000L,
                timeSinceLastReverifyMs = 10_000L
            )
        )
        assertFalse(
            HubSharkVpnService.shouldProbeSuspectedActiveStall(
                engine = ActiveVpnEngine.Hev,
                txBytesSinceLastRx = 2L * 1024L,
                txPacketsSinceLastRx = 4L,
                timeSinceLastRxMs = 5_000L,
                timeSinceLastReverifyMs = 10_000L
            )
        )
        assertTrue(
            HubSharkVpnService.shouldProbeSuspectedActiveStall(
                engine = ActiveVpnEngine.Hev,
                txBytesSinceLastRx = 2L * 1024L,
                txPacketsSinceLastRx = 4L,
                timeSinceLastRxMs = 10_000L,
                timeSinceLastReverifyMs = 10_000L
            )
        )
        assertFalse(
            HubSharkVpnService.shouldProbeSuspectedActiveStall(
                engine = ActiveVpnEngine.Hev,
                txBytesSinceLastRx = 32L * 1024L - 1L,
                txPacketsSinceLastRx = 3L,
                timeSinceLastRxMs = 10_000L,
                timeSinceLastReverifyMs = 10_000L
            )
        )
        assertFalse(
            HubSharkVpnService.shouldProbeSuspectedActiveStall(
                engine = ActiveVpnEngine.Hev,
                txBytesSinceLastRx = 32L * 1024L,
                txPacketsSinceLastRx = 0L,
                timeSinceLastRxMs = 9_999L,
                timeSinceLastReverifyMs = 10_000L
            )
        )
        assertFalse(
            HubSharkVpnService.shouldProbeSuspectedActiveStall(
                engine = ActiveVpnEngine.Hev,
                txBytesSinceLastRx = 32L * 1024L,
                txPacketsSinceLastRx = 0L,
                timeSinceLastRxMs = 10_000L,
                timeSinceLastReverifyMs = 9_999L
            )
        )
        assertFalse(
            HubSharkVpnService.shouldProbeSuspectedActiveStall(
                engine = ActiveVpnEngine.XrayNative,
                txBytesSinceLastRx = 32L * 1024L,
                txPacketsSinceLastRx = 20L,
                timeSinceLastRxMs = 10_000L,
                timeSinceLastReverifyMs = 10_000L
            )
        )
    }

    @Test
    fun activeStallRouteFailuresRequireConfirmationExceptForHardLocalFailures() {
        val timeout = RunningRouteResult(
            success = false,
            error = "public probes timed out",
            failureScope = RouteFailureScope.XrayRoute
        )
        assertTrue(
            HubSharkVpnService.shouldConfirmActiveStallFailure(
                RouteReverifyReason.ActiveStall,
                timeout
            )
        )
        assertFalse(
            HubSharkVpnService.shouldConfirmActiveStallFailure(
                RouteReverifyReason.DeviceWake,
                timeout
            )
        )
        assertFalse(
            HubSharkVpnService.shouldConfirmActiveStallFailure(
                RouteReverifyReason.ActiveStall,
                timeout.copy(
                    error = "ECONNREFUSED at 127.0.0.1",
                    failureScope = RouteFailureScope.XrayRoute
                )
            )
        )
        assertFalse(
            HubSharkVpnService.shouldConfirmActiveStallFailure(
                RouteReverifyReason.ActiveStall,
                timeout.copy(failureScope = RouteFailureScope.DialerBinding)
            )
        )
    }

    @Test
    fun activeStallWindowResetsOnResponsesAndCounterRestarts() {
        val initial = ActiveStallWindow(
            lastRxBytes = 100L,
            lastTxBytes = 100L,
            lastRxPackets = 10L,
            lastTxPackets = 10L,
            txBytesAtLastRxProgress = 100L,
            txPacketsAtLastRxProgress = 10L,
            lastRxProgressAtMs = 1_000L
        )

        val transmitOnly = HubSharkVpnService.advanceActiveStallWindow(
            current = initial,
            rxBytes = 100L,
            txBytes = 200L,
            rxPackets = 10L,
            txPackets = 14L,
            nowMs = 2_000L
        )
        assertEquals(100L, transmitOnly.txBytesAtLastRxProgress)
        assertEquals(1_000L, transmitOnly.lastRxProgressAtMs)

        val response = HubSharkVpnService.advanceActiveStallWindow(
            current = transmitOnly,
            rxBytes = 101L,
            txBytes = 250L,
            rxPackets = 11L,
            txPackets = 16L,
            nowMs = 3_000L
        )
        assertEquals(250L, response.txBytesAtLastRxProgress)
        assertEquals(16L, response.txPacketsAtLastRxProgress)
        assertEquals(3_000L, response.lastRxProgressAtMs)

        val countersRestarted = HubSharkVpnService.advanceActiveStallWindow(
            current = response,
            rxBytes = 0L,
            txBytes = 0L,
            rxPackets = 0L,
            txPackets = 0L,
            nowMs = 4_000L
        )
        assertEquals(0L, countersRestarted.txBytesAtLastRxProgress)
        assertEquals(4_000L, countersRestarted.lastRxProgressAtMs)
    }

    @Test
    fun screenWakeAfterIdleRequestsRouteReverificationOnlyForAVerifiedTunnel() {
        assertTrue(
            HubSharkVpnService.shouldReverifyAfterDeviceWake(
                tunnelState = com.hubshark.client.model.TunnelState.Connected,
                tunnelVerified = true,
                screenIdleDurationMs = 60_000L,
                timeSinceLastReverifyMs = 30_000L
            )
        )
        assertFalse(
            HubSharkVpnService.shouldReverifyAfterDeviceWake(
                tunnelState = com.hubshark.client.model.TunnelState.Connected,
                tunnelVerified = false,
                screenIdleDurationMs = 60_000L,
                timeSinceLastReverifyMs = 30_000L
            )
        )
        assertFalse(
            HubSharkVpnService.shouldReverifyAfterDeviceWake(
                tunnelState = com.hubshark.client.model.TunnelState.Connected,
                tunnelVerified = true,
                screenIdleDurationMs = 59_999L,
                timeSinceLastReverifyMs = 30_000L
            )
        )
        assertFalse(
            HubSharkVpnService.shouldReverifyAfterDeviceWake(
                tunnelState = com.hubshark.client.model.TunnelState.Connected,
                tunnelVerified = true,
                screenIdleDurationMs = 60_000L,
                timeSinceLastReverifyMs = 29_999L
            )
        )
    }

    @Test
    fun stoppedHevRuntimeIsDetectedWithoutMisclassifyingNativeOrInactiveEngines() {
        assertTrue(
            HubSharkVpnService.packetEngineRuntimeStopped(
                engine = ActiveVpnEngine.Hev,
                packetEngineExpected = true,
                nativeRuntimeRunning = false
            )
        )
        assertFalse(
            HubSharkVpnService.packetEngineRuntimeStopped(
                engine = ActiveVpnEngine.Hev,
                packetEngineExpected = true,
                nativeRuntimeRunning = true
            )
        )
        assertFalse(
            HubSharkVpnService.packetEngineRuntimeStopped(
                engine = ActiveVpnEngine.Hev,
                packetEngineExpected = false,
                nativeRuntimeRunning = false
            )
        )
        assertFalse(
            HubSharkVpnService.packetEngineRuntimeStopped(
                engine = ActiveVpnEngine.XrayNative,
                packetEngineExpected = true,
                nativeRuntimeRunning = false
            )
        )
    }

    @Test
    fun seamlessRoutePoolKeepsSelectedRouteAndOnlyCompatibleDistinctBackups() {
        val selected = liveProfile("20.0.0.1", mode = "packet-up")
        val compatibleFast = liveProfile("20.0.0.2", mode = "packet-up")
        val compatibleSecond = liveProfile("20.0.0.3", mode = "packet-up")
        val duplicateAddress = liveProfile("20.0.0.2", mode = "packet-up")
        val wrongMode = liveProfile("20.0.0.4", mode = "stream-up")
        val wrongCodespace = liveProfile("20.0.0.5", mode = "packet-up").copy(
            uuid = "11111111-1111-4111-8111-111111111111"
        )

        val selectedRoutes = HubSharkVpnService.selectSeamlessRouteProfiles(
            selected = selected,
            rankedCandidates = listOf(
                compatibleFast,
                duplicateAddress,
                wrongMode,
                wrongCodespace,
                compatibleSecond
            ),
            maxProfiles = 3
        )

        assertEquals(
            listOf("20.0.0.1", "20.0.0.2", "20.0.0.3"),
            selectedRoutes.map { it.address }
        )
    }

    @Test
    fun seamlessRoutePoolCanBeBoundedToTheSelectedRouteOnly() {
        val selected = liveProfile("20.0.0.1")
        val selectedRoutes = HubSharkVpnService.selectSeamlessRouteProfiles(
            selected = selected,
            rankedCandidates = listOf(liveProfile("20.0.0.2")),
            maxProfiles = 1
        )

        assertEquals(listOf("20.0.0.1"), selectedRoutes.map { it.address })
    }

    @Test
    fun seamlessRoutePoolOnlyTrustsFreshSuccessfulPersistedMeasurements() {
        val now = 10_000L
        val fresh = storedProfile("fresh", "20.0.0.1", 80).copy(
            lastDelayTestedAt = 9_000L, lastDelaySuccessfulSamples = 3, lastDelaySampleCount = 3
        )
        val stale = fresh.copy(id = "stale", lastDelayTestedAt = 1_000L)
        val future = fresh.copy(id = "future", lastDelayTestedAt = 11_000L)
        val failing = fresh.copy(id = "failing", delayFailureCount = 1)

        assertTrue(HubSharkVpnService.persistedRouteMeasurementFresh(fresh, now, maxAgeMs = 2_000L))
        assertFalse(HubSharkVpnService.persistedRouteMeasurementFresh(stale, now, maxAgeMs = 2_000L))
        assertFalse(HubSharkVpnService.persistedRouteMeasurementFresh(future, now, maxAgeMs = 2_000L))
        assertFalse(HubSharkVpnService.persistedRouteMeasurementFresh(failing, now, maxAgeMs = 2_000L))
        assertFalse(HubSharkVpnService.persistedRouteMeasurementFresh(
            fresh.copy(lastDelaySuccessfulSamples = 1, lastDelaySampleCount = 3), now
        ))
        assertFalse(HubSharkVpnService.persistedRouteMeasurementFresh(
            fresh.copy(lastDelaySuccessfulSamples = 1, lastDelaySampleCount = 1), now
        ))
    }

    private fun liveProfile(address: String, mode: String = "packet-up"): HubSharkProfile {
        return HubSharkProfile(
            rawUri = "vless://00000000-0000-4000-8000-000000000000@$address:443",
            name = address,
            uuid = "00000000-0000-4000-8000-000000000000",
            address = address,
            port = 443,
            security = "tls",
            sni = "space-443.app.github.dev",
            hostHeader = "space-443.app.github.dev",
            alpn = listOf("h2"),
            fingerprint = "chrome",
            transport = Transport.XHTTP,
            path = "/",
            mode = mode,
            allowInsecure = false
        )
    }
}
