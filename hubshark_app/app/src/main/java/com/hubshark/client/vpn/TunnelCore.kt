package com.hubshark.client.vpn

import com.hubshark.client.model.HubSharkProfile
import kotlinx.coroutines.flow.StateFlow

interface TunnelCore {
    suspend fun start(profile: HubSharkProfile, tunFd: Int, datDir: String, protectSocket: (Int) -> Boolean)
    suspend fun stop()
    val state: StateFlow<TunnelCoreState>
}
