package com.hubshark.client

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hubshark.client.ui.HubSharkViewModel
import com.hubshark.client.ui.HomeScreen
import com.hubshark.client.ui.SettingsScreen
import com.hubshark.client.ui.theme.MyApplicationTheme
import com.hubshark.client.model.TunnelState
import com.hubshark.client.model.VpnStateRepository
import com.hubshark.client.vpn.HubSharkVpnService

class MainActivity : ComponentActivity() {

    private val viewModel: HubSharkViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        // Re-check a tunnel after the user returns from another app. The
        // foreground service remains the owner of recovery; this only asks it
        // to verify the existing live route and repair it if necessary.
        if (VpnStateRepository.state.value.tunnelState == TunnelState.Connected) {
            startService(Intent(this, HubSharkVpnService::class.java).apply {
                action = HubSharkVpnService.ACTION_REVERIFY
            })
        } else {
            viewModel.autoOptimizeRoutesIfNeeded()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()

                    NavHost(navController = navController, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                viewModel = viewModel,
                                onNavigateToSettings = { navController.navigate("settings") }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                viewModel = viewModel,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
