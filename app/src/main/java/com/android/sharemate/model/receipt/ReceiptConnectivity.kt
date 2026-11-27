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
          val stateLock = Any()
          var receivedCallback = false
          fun publishCallback(connected: Boolean) {
            synchronized(stateLock) {
              receivedCallback = true
              trySend(connected)
            }
          }
          val callback =
              object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) = publishCallback(false)

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) =
                    publishCallback(
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
              }
          manager.registerDefaultNetworkCallback(callback)
          // Seed the initial state only; callback arguments are authoritative after registration.
          // onAvailable is followed by onCapabilitiesChanged, so it needs no synchronous query.
          val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
          synchronized(stateLock) {
            // A callback may arrive while the initial snapshot is being read.
            if (!receivedCallback) {
              trySend(
                  capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            }
          }
          awaitClose { manager.unregisterNetworkCallback(callback) }
        }
        .distinctUntilChanged()
