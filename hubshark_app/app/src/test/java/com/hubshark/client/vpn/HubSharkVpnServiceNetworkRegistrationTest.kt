package com.hubshark.client.vpn

import android.os.Build
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class HubSharkVpnServiceNetworkRegistrationTest {
    private var service: HubSharkVpnService? = null

    @After
    fun tearDown() {
        service?.onDestroy()
        service = null
    }

    @Test
    fun androidSWaitsForBestMatchingPhysicalNetworkBeforeTunnelStartup() {
        val created = Robolectric.buildService(HubSharkVpnService::class.java).create().get()
        service = created

        HubSharkVpnService::class.java
            .getDeclaredMethod("registerUnderlyingNetworkMonitor")
            .apply { isAccessible = true }
            .invoke(created)

        val waiting = HubSharkVpnService::class.java
            .getDeclaredField("waitingForUnderlyingNetwork")
            .apply { isAccessible = true }
            .getBoolean(created)

        assertTrue(waiting)
    }
}
