// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.finance.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptBalanceViewModelTest {
  @Test
  fun failedObservationReconnectsAndClearingTheModelStopsIt() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val owner = ViewModelStore()
    var attempts = 0
    var stopped = false
    try {
      val repository = mock(HouseholdFinanceRepository::class.java)
      `when`(repository.observeHouseholdFinances("home"))
          .thenReturn(
              kotlinx.coroutines.flow.flow {
                if (++attempts == 1) throw java.io.IOException("Offline")
                emit(HouseholdFinanceSnapshot("home", emptyList(), emptyList()))
                try {
                  kotlinx.coroutines.awaitCancellation()
                } finally {
                  stopped = true
                }
              })
      val model = ReceiptBalanceViewModel(repository, "home", "a")
      owner.put("balance", model)
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.balance.collect() }
      runCurrent()
      assertNull(model.balance.value)
      assertEquals(1, attempts)
      advanceTimeBy(com.android.sharemate.model.receipt.RECEIPT_INITIAL_RETRY_MILLIS)
      runCurrent()
      assertEquals(0L, model.balance.value)
      assertEquals(2, attempts)
      owner.clear()
      runCurrent()
      assertTrue(stopped)
      advanceTimeBy(com.android.sharemate.model.receipt.RECEIPT_MAX_RETRY_MILLIS)
      runCurrent()
      assertEquals(2, attempts)
    } finally {
      owner.clear()
      runCurrent()
      Dispatchers.resetMain()
    }
  }

  @Test
  fun confirmedBalancesUpdateAndCachedDataIsUnknown() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val owner = ViewModelStore()
    try {
      val ledger =
          MutableStateFlow(HouseholdFinanceSnapshot("home", emptyList(), emptyList(), false))
      val repository = mock(HouseholdFinanceRepository::class.java)
      `when`(repository.observeHouseholdFinances("home")).thenReturn(ledger)
      val model = ReceiptBalanceViewModel(repository, "home", "a")
      owner.put("balance", model)
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.balance.collect() }
      runCurrent()
      assertNull(model.balance.value)
      ledger.value =
          HouseholdFinanceSnapshot(
              "home",
              listOf(SharedExpense("receipt", "home", "b", 101, mapOf("a" to 51L, "b" to 50L))),
              emptyList())
      runCurrent()
      assertEquals(-51L, model.balance.value)
      ledger.value = HouseholdFinanceSnapshot("home", emptyList(), emptyList())
      runCurrent()
      assertEquals(0L, model.balance.value)
      ledger.value = HouseholdFinanceSnapshot("home", emptyList(), emptyList(), false)
      runCurrent()
      assertNull(model.balance.value)
    } finally {
      owner.clear()
      runCurrent()
      Dispatchers.resetMain()
    }
  }
}
