package com.antoniopaess.netlens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.antoniopaess.netlens.designsystem.NetLensTheme
import dagger.hilt.android.AndroidEntryPoint

private const val HOST_ROUTE = "host"

/** Minimal Android entry point; feature destinations are added in their implementation commits. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NetLensTheme {
                NetLensNavHost()
            }
        }
    }
}

@Composable
private fun NetLensNavHost() {
    val navController = rememberNavController()

    // Keep a real destination so the host can boot before the feature graphs exist.
    NavHost(navController = navController, startDestination = HOST_ROUTE) {
        composable(HOST_ROUTE) {}
    }
}
