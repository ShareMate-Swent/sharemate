// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.finance

import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.finance.*
import com.android.sharemate.model.household.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class BalancesViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val session = MutableStateFlow<AuthSession?>(AuthSession("alice", null))
  private val auth = mock(AuthRepository::class.java)
  private val households = mock(HouseholdRepository::class.java)
  private val home = Household(id = "home", memberIds = listOf("alice", "bob", "zero"))
  private val ledger = MutableStateFlow(snapshot())
  private var failure = false
  private var active = 0
  private var subscriptions = 0
  private val finances =
      object : HouseholdExpenseRepository {
        override fun observeHouseholdExpenses(householdId: String) = flow {
          subscriptions++
          active++
          try {
            if (failure) error("denied")
            emitAll(ledger)
          } finally {
            active--
          }
        }
      }

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    `when`(auth.session).thenReturn(session)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private suspend fun create(household: Household? = home): BalancesViewModel {
    `when`(households.getHousehold("home")).thenReturn(household)
    return BalancesViewModel(finances, households, auth, "home", dispatcher)
  }

  @Test
  fun loadingThenEmptyAndReactiveDebtsIncludeZeroActivityMembers() = runTest {
    val vm = create()
    assertEquals(BalancesUiState.Loading, vm.state.value)
    advanceUntilIdle()
    val empty = vm.state.value as BalancesUiState.Ready
    assertEquals(mapOf("alice" to 0L, "bob" to 0L, "zero" to 0L), empty.summary.netBalancesCents)
    assertTrue(empty.summary.debts.isEmpty())
    ledger.value = snapshot(SharedExpense("e", "home", "bob", 1500, mapOf("alice" to 1500L)))
    advanceUntilIdle()
    val ready = vm.state.value as BalancesUiState.Ready
    assertEquals("alice", ready.currentUserId)
    assertEquals(-1500L, ready.summary.netBalancesCents["alice"])
    assertEquals(listOf(Debt("alice", "bob", 1500)), ready.summary.debts)
    vm.retry()
    advanceUntilIdle()
    assertEquals(1, subscriptions)
    session.value = null
    advanceUntilIdle()
    assertEquals(BalancesUiState.Unavailable, vm.state.value)
    assertEquals(0, active)
  }

  @Test
  fun failureClearsAmountsAndRetryReloadsMembershipWithoutDuplicateObservers() = runTest {
    ledger.value = snapshot(SharedExpense("e", "home", "alice", 100, mapOf("bob" to 100L)))
    val vm = create()
    advanceUntilIdle()
    assertTrue(vm.state.value is BalancesUiState.Ready)
    ledger.value = listOf(SharedExpense("foreign", "other", "alice", 100, mapOf("bob" to 100L)))
    advanceUntilIdle()
    assertEquals(BalancesUiState.Error, vm.state.value)
    assertEquals(0, active)
    failure = true
    vm.retry()
    vm.retry()
    advanceUntilIdle()
    assertEquals(BalancesUiState.Error, vm.state.value)
    assertEquals(2, subscriptions)
    failure = false
    ledger.value = snapshot()
    vm.retry()
    advanceUntilIdle()
    assertTrue(vm.state.value is BalancesUiState.Ready)
    verify(households, times(3)).getHousehold("home")
    session.value = AuthSession("outsider", null)
    advanceUntilIdle()
    assertEquals(BalancesUiState.Unavailable, vm.state.value)
    assertEquals(0, active)
  }

  @Test
  fun signedOutMissingPrivateAndNonmemberHouseholdsNeverSubscribe() = runTest {
    for (household in
        listOf(null, home.copy(type = FridgeType.PRIVATE), home.copy(memberIds = emptyList()))) {
      val vm = create(household)
      advanceUntilIdle()
      assertEquals(BalancesUiState.Unavailable, vm.state.value)
    }
    session.value = null
    val vm = create()
    advanceUntilIdle()
    assertEquals(BalancesUiState.Unavailable, vm.state.value)
    assertEquals(0, subscriptions)
  }

  @Test
  fun householdLookupFailureCanRecoverAndChangedAccountGetsItsOwnBalance() = runTest {
    val vm = create()
    `when`(households.getHousehold("home")).thenThrow(IllegalStateException("offline"))
    advanceUntilIdle()
    assertEquals(BalancesUiState.Error, vm.state.value)
    doReturn(home).`when`(households).getHousehold("home")
    vm.retry()
    advanceUntilIdle()
    session.value = AuthSession("bob", null)
    advanceUntilIdle()
    assertEquals("bob", (vm.state.value as BalancesUiState.Ready).currentUserId)
    assertEquals(1, active)
    session.value = null
    advanceUntilIdle()
    assertEquals(0, active)
  }

  private fun snapshot(vararg expenses: SharedExpense) = expenses.toList()
}
