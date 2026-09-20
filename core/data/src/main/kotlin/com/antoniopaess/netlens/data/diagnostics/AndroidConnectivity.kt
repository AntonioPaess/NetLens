package com.antoniopaess.netlens.data.diagnostics

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.antoniopaess.netlens.domain.ConnectivityObserver
import com.antoniopaess.netlens.domain.ConnectivitySnapshot
import com.antoniopaess.netlens.domain.ConnectivitySnapshotProvider
import com.antoniopaess.netlens.domain.NetworkTransport
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Adapts the Android callback lifecycle to a cold domain flow.
 *
 * Collection emits a snapshot immediately, registers one default network
 * callback, and unregisters that exact callback when collection is cancelled.
 * No callback or Context escapes the adapter.
 */
class AndroidConnectivityObserver(
    context: Context,
) : ConnectivityObserver {
    private val applicationContext = context.applicationContext

    @SuppressLint("MissingPermission")
    override fun observe(): Flow<ConnectivitySnapshot> =
        callbackFlow {
            val manager =
                applicationContext.getSystemService(ConnectivityManager::class.java)
                    ?: run {
                        trySend(ConnectivitySnapshot(false, false, emptySet()))
                        close()
                        awaitClose { }
                        return@callbackFlow
                    }

            trySend(manager.snapshot())
            var registered = false
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        trySend(manager.snapshot())
                    }

                    override fun onLost(network: Network) {
                        trySend(manager.snapshot())
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        trySend(manager.snapshot())
                    }
                }

            try {
                manager.registerDefaultNetworkCallback(callback)
                registered = true
            } catch (_: RuntimeException) {
                trySend(ConnectivitySnapshot(false, false, emptySet()))
                close()
            }

            awaitClose {
                if (registered) {
                    runCatching { manager.unregisterNetworkCallback(callback) }
                }
            }
        }
}

/** Supplies the latest Android snapshot to a diagnostic probe. */
class AndroidConnectivitySnapshotProvider(
    context: Context,
) : ConnectivitySnapshotProvider {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    @SuppressLint("MissingPermission")
    override fun current(): ConnectivitySnapshot =
        manager?.snapshot()
            ?: ConnectivitySnapshot(false, false, emptySet())
}

@SuppressLint("MissingPermission")
private fun ConnectivityManager.snapshot(): ConnectivitySnapshot {
    val network = activeNetwork ?: return ConnectivitySnapshot(false, false, emptySet())
    val capabilities =
        getNetworkCapabilities(network)
            ?: return ConnectivitySnapshot(false, false, emptySet())

    return ConnectivitySnapshot(
        available = true,
        validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        transports =
            buildSet {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(NetworkTransport.WIFI)
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    add(NetworkTransport.CELLULAR)
                }
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    add(NetworkTransport.ETHERNET)
                }
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(NetworkTransport.VPN)
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
                    add(NetworkTransport.BLUETOOTH)
                }
                if (isEmpty()) add(NetworkTransport.OTHER)
            },
    )
}
