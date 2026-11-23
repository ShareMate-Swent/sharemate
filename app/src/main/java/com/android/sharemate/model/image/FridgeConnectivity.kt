// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn

internal fun ConnectivityManager.observeFridgeConnectivity(scope: CoroutineScope) =
    callbackFlow {
          val callback =
              object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                  trySend(networkCapabilities.hasInternet())
                }

                override fun onLost(network: Network) {
                  trySend(isOnline())
                }
              }
          registerDefaultNetworkCallback(callback)
          trySend(isOnline())
          awaitClose { unregisterNetworkCallback(callback) }
        }
        .stateIn(scope, SharingStarted.Eagerly, isOnline())

private fun ConnectivityManager.isOnline() = getNetworkCapabilities(activeNetwork).hasInternet()

private fun NetworkCapabilities?.hasInternet() =
    this != null &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
