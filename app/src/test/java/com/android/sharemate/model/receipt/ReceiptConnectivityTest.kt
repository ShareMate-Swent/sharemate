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
  fun validatedCallbackWinsWhenSynchronousNetworkQueryIsStillNull() = runTest {
    val fixture = ConnectivityFixture()
    val states = mutableListOf<Boolean?>()
    val job =
        backgroundScope.launch { receiptConnectivity(fixture.context).collect { states += it } }
    runCurrent()
    assertEquals(listOf(false), states)

    // Android has delivered validation, but activeNetwork still returns null.
    fixture.callback.onAvailable(fixture.network)
    runCurrent()
    assertEquals(listOf(false), states)
    fixture.callback.onCapabilitiesChanged(fixture.network, fixture.validatedCapabilities)
    runCurrent()
    assertEquals(listOf(false, true), states)
    job.cancel()
  }

  @Test
  fun lostCallbackWinsWhenSynchronousQueryStillReportsValidatedNetwork() = runTest {
    val fixture = ConnectivityFixture()
    `when`(fixture.manager.activeNetwork).thenReturn(fixture.network)
    `when`(fixture.manager.getNetworkCapabilities(fixture.network))
        .thenReturn(fixture.validatedCapabilities)
    val states = mutableListOf<Boolean?>()
    backgroundScope.launch { receiptConnectivity(fixture.context).collect { states += it } }
    runCurrent()
    assertEquals(listOf(true), states)

    fixture.callback.onLost(fixture.network)
    runCurrent()
    assertEquals(listOf(true, false), states)
  }

  @Test
  fun callbackDuringInitialQueryIsNotOverwrittenByStaleSnapshot() = runTest {
    val fixture = ConnectivityFixture()
    `when`(fixture.manager.activeNetwork).thenAnswer {
      fixture.callback.onCapabilitiesChanged(fixture.network, fixture.validatedCapabilities)
      null
    }
    val states = mutableListOf<Boolean?>()
    backgroundScope.launch { receiptConnectivity(fixture.context).collect { states += it } }
    runCurrent()
    assertEquals(listOf(true), states)
  }

  @Test
  fun capabilitiesCanLoseValidationWithoutLosingTheNetwork() = runTest {
    val fixture = ConnectivityFixture()
    val states = mutableListOf<Boolean?>()
    backgroundScope.launch { receiptConnectivity(fixture.context).collect { states += it } }
    runCurrent()
    fixture.callback.onCapabilitiesChanged(fixture.network, fixture.validatedCapabilities)
    runCurrent()
    fixture.callback.onCapabilitiesChanged(fixture.network, mock(NetworkCapabilities::class.java))
    runCurrent()
    assertEquals(listOf(false, true, false), states)
  }

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

  private class ConnectivityFixture {
    val context = mock(Context::class.java)
    val manager = mock(ConnectivityManager::class.java)
    val network = mock(Network::class.java)
    val validatedCapabilities = mock(NetworkCapabilities::class.java)
    lateinit var callback: ConnectivityManager.NetworkCallback

    init {
      `when`(context.getSystemService(ConnectivityManager::class.java)).thenReturn(manager)
      `when`(validatedCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
          .thenReturn(true)
      doAnswer {
            callback = it.getArgument(0)
            null
          }
          .`when`(manager)
          .registerDefaultNetworkCallback(any(ConnectivityManager.NetworkCallback::class.java))
    }
  }
}
