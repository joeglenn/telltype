package net.jbglenn.telltype.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withTimeoutOrNull
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.domain.port.ServerHealthSource

/**
 * Follows the device's default network while dictation runs. When it changes (Wi-Fi to mobile, VPN up or down,
 * lost), the server health is probed again, so the bubble's offline mark is current (docs/PLAN.md section 11).
 * Each role has exactly one address (D60), so there is no remembered address to forget.
 */
internal class NetworkWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val health: ServerHealthSource,
    private val logger: Logger,
) {
    /** Watches until cancelled; the callback is unregistered on cancellation. */
    suspend fun watch() {
        defaultNetwork().distinctUntilChanged().collectIndexed { index, _ ->
            if (index > 0) {
                logger.info(TAG) { "default network changed" }
            }
            withTimeoutOrNull(PROBE_LIMIT) { health.probe() }
        }
    }

    private fun defaultNetwork() = callbackFlow<Network?> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(network)
            }

            override fun onLost(network: Network) {
                trySend(null)
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }

    private companion object {
        val TAG = LogTag("NetworkWatcher")
        val PROBE_LIMIT = 15.seconds
    }
}
