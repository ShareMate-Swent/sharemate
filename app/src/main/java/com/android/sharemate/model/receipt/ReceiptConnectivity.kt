// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.receipt

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Connectivity is a UI hint, never proof that Firestore received or accepted a write. */
fun receiptConnectivity(context: Context): Flow<Boolean?> =
    callbackFlow {
          val manager = context.getSystemService(ConnectivityManager::class.java)
          if (manager == null) {
            trySend(null)
            close()
            return@callbackFlow
          }
          fun publish() {
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            trySend(
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
          }
          val callback =
              object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = publish()

                override fun onLost(network: Network) = publish()

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) = publish()
              }
          manager.registerDefaultNetworkCallback(callback)
          publish()
          awaitClose { manager.unregisterNetworkCallback(callback) }
        }
        .distinctUntilChanged()
