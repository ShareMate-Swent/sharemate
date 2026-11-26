// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.receipt

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptConnectivityTest {
  @Test
  fun connectionChangesAreObservedAndCallbackIsReleased() = runTest {
    val context = mock(Context::class.java)
    val manager = mock(ConnectivityManager::class.java)
    val network = mock(Network::class.java)
    val capabilities = mock(NetworkCapabilities::class.java)
    `when`(context.getSystemService(ConnectivityManager::class.java)).thenReturn(manager)
    lateinit var callback: ConnectivityManager.NetworkCallback
    doAnswer {
          callback = it.getArgument(0)
          null
        }
        .`when`(manager)
        .registerDefaultNetworkCallback(any(ConnectivityManager.NetworkCallback::class.java))
    val states = mutableListOf<Boolean?>()
    val job = launch { receiptConnectivity(context).collect { states += it } }
    runCurrent()
    assertEquals(listOf(false), states)
    `when`(manager.activeNetwork).thenReturn(network)
    `when`(manager.getNetworkCapabilities(network)).thenReturn(capabilities)
    `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        .thenReturn(true)
    callback.onCapabilitiesChanged(network, capabilities)
    runCurrent()
    callback.onAvailable(network)
    runCurrent()
    assertEquals(listOf(false, true), states)
    `when`(manager.activeNetwork).thenReturn(null)
    callback.onLost(network)
    runCurrent()
    assertEquals(listOf(false, true, false), states)
    job.cancel()
    runCurrent()
    verify(manager).unregisterNetworkCallback(callback)
  }

  @Test
  fun unavailableServiceIsUnknownRatherThanOffline() = runTest {
    val states = mutableListOf<Boolean?>()
    receiptConnectivity(mock(Context::class.java)).collect { states += it }
    assertEquals(listOf<Boolean?>(null), states)
  }
}
