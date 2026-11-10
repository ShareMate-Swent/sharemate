// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.finance

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.auth.*
import com.android.sharemate.model.finance.*
import com.android.sharemate.model.household.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class SettlementViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val session = MutableStateFlow<AuthSession?>(AuthSession("alice", null))
  private val auth = mock(AuthRepository::class.java)
  private val households = mock(HouseholdRepository::class.java)
  private val home = Household(id = "home", memberIds = listOf("alice", "bob"))
  private val expense = SharedExpense("e", "home", "bob", 1500, mapOf("alice" to 1500L))
  private val ledger = MutableStateFlow(snapshot())
  private val requests = mutableListOf<Settlement>()
  private var answer = CompletableDeferred<Settlement>()
  private var listenerFailure = false
  private var silent = false
  private var nextOperation = 0
  private var subscriptions = 0
  private val store = ViewModelStore()
  private val repository =
      object : HouseholdFinanceRepository {
        override fun observeHouseholdFinances(householdId: String) = flow {
          subscriptions++
          if (listenerFailure) error("denied")
          if (silent) awaitCancellation()
          emitAll(ledger)
        }

        override suspend fun recordSettlement(settlement: Settlement): Settlement {
          requests += settlement
          return answer.await()
        }
      }

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    `when`(auth.session).thenReturn(session)
  }

  @After
  fun tearDown() {
    store.clear()
    Dispatchers.resetMain()
  }

  private suspend fun create(
      saved: SavedStateHandle = SavedStateHandle(),
      household: Household? = home,
      profiles: com.android.sharemate.model.profile.UserProfileRepository? = null,
  ): SettlementViewModel {
    `when`(households.getHousehold("home")).thenReturn(household)
    return SettlementViewModel(
            repository, households, auth, "home", "alice", saved, dispatcher, profiles) {
              "operation-${nextOperation++}"
            }
        .also { store.put("vm${store.keys().size}", it) }
  }

  private fun snapshot(vararg payments: Settlement) =
      HouseholdFinanceSnapshot("home", listOf(expense), payments.toList())

  private fun ready(vm: SettlementViewModel) = vm.state.value as SettlementUiState.Ready

  private fun debt(vm: SettlementViewModel) = ready(vm).summary.debts.single()

  private fun remaining(vm: SettlementViewModel) =
      ready(vm).summary.netBalancesCents.getValue("bob")

  @Test
  fun silentLedgerTimesOutAndRetryRecoversWithSafeNamesAndInvalidDataClearsAmounts() = runTest {
    val profiles = mock(com.android.sharemate.model.profile.UserProfileRepository::class.java)
    `when`(profiles.observeMembers("home"))
        .thenReturn(
            flowOf(
                listOf(
                    com.android.sharemate.model.profile.UserProfile("bob", " Bob "),
                    com.android.sharemate.model.profile.UserProfile("alice", " "))))
    silent = true
    val vm = create(profiles = profiles)
    runCurrent()
    advanceTimeBy(9999)
    runCurrent()
    assertEquals(SettlementUiState.Loading, vm.state.value)
    advanceTimeBy(1)
    runCurrent()
    assertEquals(SettlementUiState.Offline, vm.state.value)
    silent = false
    vm.retryLoading()
    advanceUntilIdle()
    assertEquals(mapOf("bob" to "Bob"), ready(vm).memberNames)
    ledger.value = HouseholdFinanceSnapshot("other", emptyList(), emptyList())
    advanceUntilIdle()
    assertEquals(SettlementUiState.InvalidData, vm.state.value)
    assertFalse(vm.settle(Debt("alice", "bob", 1500), 1))
  }

  @Test
  fun missingProfilesDoNotHideFinancialDataAndSignOutClearsNames() = runTest {
    val profiles = mock(com.android.sharemate.model.profile.UserProfileRepository::class.java)
    `when`(profiles.observeMembers("home"))
        .thenReturn(flow { throw java.io.IOException("offline") })
    val vm = create(profiles = profiles)
    advanceUntilIdle()
    assertEquals(1500L, remaining(vm))
    assertTrue(ready(vm).memberNames.isEmpty())
    session.value = null
    advanceUntilIdle()
    assertEquals(SettlementUiState.Unavailable, vm.state.value)
  }

  @Test
  fun optimisticPartialConfirmationAndFullPaymentNeverDoubleCount() = runTest {
    val vm = create()
    assertEquals(SettlementUiState.Loading, vm.state.value)
    advanceUntilIdle()
    val original = debt(vm)
    assertTrue(vm.settle(original, 600))
    assertFalse(vm.settle(original, 600))
    advanceUntilIdle()
    assertEquals(900L, remaining(vm))
    assertEquals(SettlementPhase.SUBMITTING, ready(vm).submission.phase)
    val payment = requests.single()
    answer.complete(payment)
    advanceUntilIdle()
    assertEquals(SettlementPhase.AWAITING_CONFIRMATION, ready(vm).submission.phase)
    assertFalse(vm.settle(debt(vm), 900))
    ledger.value = snapshot(payment)
    advanceUntilIdle()
    assertEquals(900L, remaining(vm))
    assertEquals(SettlementPhase.IDLE, ready(vm).submission.phase)
    assertFalse(vm.settle(original, 600))
    answer = CompletableDeferred()
    assertTrue(vm.settle(debt(vm), 900))
    advanceUntilIdle()
    assertEquals(0L, remaining(vm))
    val full = requests.last()
    ledger.value = snapshot(payment, full)
    advanceUntilIdle()
    // Observation is enough even while the callable response is unresolved.
    assertTrue(ready(vm).summary.debts.isEmpty())
    assertEquals(SettlementPhase.IDLE, ready(vm).submission.phase)
  }

  @Test
  fun rejectedPaymentRollsBackAndRetriesWithIdenticalPayload() = runTest {
    val vm = create()
    advanceUntilIdle()
    vm.settle(debt(vm), 600)
    advanceUntilIdle()
    val payment = requests.single()
    answer.completeExceptionally(IllegalStateException("rejected"))
    advanceUntilIdle()
    assertEquals(1500L, remaining(vm))
    assertEquals(SettlementPhase.FAILED, ready(vm).submission.phase)
    assertFalse(vm.settle(debt(vm), 100))
    answer = CompletableDeferred()
    vm.retrySettlement()
    vm.retrySettlement()
    advanceUntilIdle()
    assertEquals(listOf(payment, payment), requests)
    assertEquals(900L, remaining(vm))
    ledger.value = snapshot(payment)
    advanceUntilIdle()
    answer.completeExceptionally(IllegalStateException("response lost after observation"))
    advanceUntilIdle()
    assertEquals(SettlementPhase.IDLE, ready(vm).submission.phase)
    assertEquals(900L, remaining(vm))
  }

  @Test
  fun repositoryCancellationRollsBackAndRetriesWithSavedPayload() = runTest {
    val saved = SavedStateHandle()
    val vm = create(saved)
    advanceUntilIdle()
    assertTrue(vm.settle(debt(vm), 600))
    advanceUntilIdle()
    val payment = requests.single()
    assertEquals(900L, remaining(vm))
    answer.cancel(CancellationException("repository cancelled"))
    advanceUntilIdle()
    assertEquals(1500L, remaining(vm))
    assertEquals(SettlementPhase.FAILED, ready(vm).submission.phase)
    assertEquals(payment, ready(vm).submission.operation)
    assertFalse(ready(vm).submission.optimistic)
    assertEquals(
        arrayListOf(payment.id, payment.recipientId, payment.amountCents.toString()),
        saved.get<ArrayList<String>>("settlement_home_alice"))
    assertFalse(vm.settle(debt(vm), 100))
    answer = CompletableDeferred()
    vm.retrySettlement()
    vm.retrySettlement()
    advanceUntilIdle()
    assertEquals(listOf(payment, payment), requests)
    assertEquals(SettlementPhase.SUBMITTING, ready(vm).submission.phase)
    assertEquals(900L, remaining(vm))
    answer.complete(payment)
    advanceUntilIdle()
    assertEquals(SettlementPhase.AWAITING_CONFIRMATION, ready(vm).submission.phase)
    ledger.value = snapshot(payment)
    advanceUntilIdle()
    assertEquals(SettlementPhase.IDLE, ready(vm).submission.phase)
    assertEquals(900L, remaining(vm))
    assertNull(saved.get<ArrayList<String>>("settlement_home_alice"))
  }

  @Test
  fun recreationRetainsOriginalOperationAndReconcilesAnUncertainCommit() = runTest {
    val saved = SavedStateHandle()
    val vm = create(saved)
    advanceUntilIdle()
    vm.settle(debt(vm), 600)
    advanceUntilIdle()
    val payment = requests.single()
    val restored =
        SavedStateHandle(
            mapOf("settlement_home_alice" to saved.get<ArrayList<String>>("settlement_home_alice")))
    store.clear()
    val recreated = create(restored)
    advanceUntilIdle()
    assertEquals(SettlementPhase.FAILED, ready(recreated).submission.phase)
    assertEquals(1500L, remaining(recreated))
    answer = CompletableDeferred()
    recreated.retrySettlement()
    advanceUntilIdle()
    assertEquals(payment, requests.last())
    ledger.value = snapshot(payment)
    advanceUntilIdle()
    assertNull(restored.get<ArrayList<String>>("settlement_home_alice"))
    assertEquals(900L, remaining(recreated))
  }

  @Test
  fun guardsRejectInvalidAmountsForeignPayersAndStaleDebts() = runTest {
    val vm = create()
    assertFalse(vm.settle(Debt("alice", "bob", 1500), 600))
    vm.retrySettlement()
    advanceUntilIdle()
    for (amount in listOf(Long.MIN_VALUE, -1L, 0L, 1501L, Long.MAX_VALUE)) assertFalse(
        vm.settle(debt(vm), amount))
    assertFalse(vm.settle(Debt("bob", "alice", 1500), 1))
    assertFalse(vm.settle(Debt("alice", "carol", 1500), 1))
    assertFalse(vm.settle(Debt("alice", "bob", 1501), 1))
    assertTrue(requests.isEmpty())
    assertTrue(vm.settle(debt(vm), 1))
    advanceUntilIdle()
    assertEquals(1499L, remaining(vm))
  }

  @Test
  fun staleDebtOnUncertainRetryDoesNotPreviewAnOverpayment() = runTest {
    val vm = create()
    advanceUntilIdle()
    vm.settle(debt(vm), 600)
    advanceUntilIdle()
    answer.completeExceptionally(IllegalStateException("timeout"))
    advanceUntilIdle()
    ledger.value = snapshot(Settlement("other", "home", "alice", "bob", 1400))
    advanceUntilIdle()
    answer = CompletableDeferred()
    vm.retrySettlement()
    advanceUntilIdle()
    assertEquals(requests.first(), requests.last())
    assertEquals(100L, remaining(vm))
    answer.completeExceptionally(IllegalStateException("stale"))
    advanceUntilIdle()
    assertEquals(100L, remaining(vm))
  }

  @Test
  fun accessAndListenerFailuresHideBalancesAndRetryReloadsMembership() = runTest {
    listenerFailure = true
    val vm = create()
    advanceUntilIdle()
    assertEquals(SettlementUiState.Error, vm.state.value)
    assertFalse(vm.settle(Debt("alice", "bob", 1500), 1))
    listenerFailure = false
    vm.retryLoading()
    advanceUntilIdle()
    assertEquals(1500L, remaining(vm))
    assertEquals(2, subscriptions)
    vm.retryLoading()
    session.value = AuthSession("bob", null)
    advanceUntilIdle()
    assertEquals(SettlementUiState.Unavailable, vm.state.value)
    assertEquals(2, subscriptions)
    session.value = null
    advanceUntilIdle()
    assertEquals(SettlementUiState.Unavailable, vm.state.value)
  }

  @Test
  fun missingPrivateAndNonmemberHouseholdsNeverObserve() = runTest {
    for (household in
        listOf(null, home.copy(type = FridgeType.PRIVATE), home.copy(memberIds = listOf("bob")))) {
      val vm = create(household = household)
      advanceUntilIdle()
      assertEquals(SettlementUiState.Unavailable, vm.state.value)
      store.clear()
    }
    assertEquals(0, subscriptions)
  }

  @Test
  fun observedConfirmationReleasesGuardBeforeNetworkResponse() = runTest {
    val vm = create()
    advanceUntilIdle()
    vm.settle(debt(vm), 600)
    advanceUntilIdle()
    ledger.value = snapshot(requests.single())
    advanceUntilIdle()
    assertEquals(SettlementPhase.IDLE, ready(vm).submission.phase)
    assertTrue(vm.settle(debt(vm), 900))
    advanceUntilIdle()
    assertEquals(2, requests.size)
    assertNotEquals(requests.first().id, requests.last().id)
    assertEquals(0L, remaining(vm))
  }

  @Test
  fun definitiveRejectionAllowsCorrectedAmountAndClearsSavedOperation() = runTest {
    val saved = SavedStateHandle()
    val vm = create(saved)
    advanceUntilIdle()
    vm.settle(debt(vm), 600)
    advanceUntilIdle()
    ledger.value = snapshot(Settlement("concurrent", "home", "alice", "bob", 1400))
    // Observe the reduced debt before the rejection; a later ledger update clears the banner.
    advanceUntilIdle()
    answer.completeExceptionally(SettlementRejectedException())
    advanceUntilIdle()
    assertEquals(100L, remaining(vm))
    assertEquals(SettlementPhase.REJECTED, ready(vm).submission.phase)
    assertNull(saved.get<ArrayList<String>>("settlement_home_alice"))
    vm.retrySettlement()
    assertEquals(1, requests.size)
    answer = CompletableDeferred()
    assertTrue(vm.settle(debt(vm), 100))
    advanceUntilIdle()
    assertNotEquals(requests.first().id, requests.last().id)
    assertEquals(0L, remaining(vm))
  }

  @Test
  fun changedLedgerClearsDefinitiveRejectionEvenWhenDebtDisappears() = runTest {
    val vm = create()
    advanceUntilIdle()
    assertTrue(vm.settle(debt(vm), 600))
    advanceUntilIdle()
    answer.completeExceptionally(SettlementRejectedException())
    advanceUntilIdle()
    assertEquals(SettlementPhase.REJECTED, ready(vm).submission.phase)

    ledger.value = snapshot(Settlement("other", "home", "alice", "bob", 1500))
    advanceUntilIdle()
    assertEquals(SettlementSubmission(), ready(vm).submission)
    assertTrue(ready(vm).summary.debts.isEmpty())
  }

  @Test
  fun namesOnlyChangePreservesDefinitiveRejection() = runTest {
    val profiles = mock(com.android.sharemate.model.profile.UserProfileRepository::class.java)
    val names =
        MutableStateFlow(listOf(com.android.sharemate.model.profile.UserProfile("bob", "Bob")))
    `when`(profiles.observeMembers("home")).thenReturn(names)
    val vm = create(profiles = profiles)
    advanceUntilIdle()
    assertTrue(vm.settle(debt(vm), 600))
    advanceUntilIdle()
    answer.completeExceptionally(SettlementRejectedException())
    advanceUntilIdle()
    val rejection = ready(vm).submission
    assertEquals(SettlementPhase.REJECTED, rejection.phase)

    names.value = listOf(com.android.sharemate.model.profile.UserProfile("bob", "Robert"))
    advanceUntilIdle()
    assertEquals(mapOf("bob" to "Robert"), ready(vm).memberNames)
    assertEquals(rejection, ready(vm).submission)
    assertEquals(1500L, remaining(vm))
  }

  @Test
  fun observationRestartClearsDefinitiveRejectionAndSavedOperation() = runTest {
    val saved = SavedStateHandle()
    val vm = create(saved)
    advanceUntilIdle()
    assertTrue(vm.settle(debt(vm), 600))
    advanceUntilIdle()
    answer.completeExceptionally(SettlementRejectedException())
    advanceUntilIdle()
    assertEquals(SettlementPhase.REJECTED, ready(vm).submission.phase)

    session.value = null
    advanceUntilIdle()
    session.value = AuthSession("alice", null)
    advanceUntilIdle()
    assertEquals(2, subscriptions)
    assertEquals(SettlementSubmission(), ready(vm).submission)
    assertNull(saved.get<ArrayList<String>>("settlement_home_alice"))
    assertEquals(1500L, remaining(vm))
  }

  @Test
  fun changedLedgerCannotCrashOptimisticRenderingAndRetryCanRecover() = runTest {
    val vm = create()
    advanceUntilIdle()
    vm.settle(debt(vm), 600)
    advanceUntilIdle()
    ledger.value =
        HouseholdFinanceSnapshot(
            "home",
            listOf(
                SharedExpense(
                    "reversed", "home", "alice", Long.MAX_VALUE, mapOf("bob" to Long.MAX_VALUE))),
            emptyList())
    advanceUntilIdle()
    assertEquals(SettlementUiState.Error, vm.state.value)
    vm.retryLoading()
    advanceUntilIdle()
    assertEquals(Long.MAX_VALUE, ready(vm).summary.netBalancesCents["alice"])
    assertEquals(SettlementPhase.FAILED, ready(vm).submission.phase)
  }

  @Test
  fun accountSwitchCancelsWriteAndReturningAccountRetainsRetryIdentity() = runTest {
    val vm = create()
    advanceUntilIdle()
    vm.settle(debt(vm), 600)
    advanceUntilIdle()
    val payment = requests.single()
    session.value = AuthSession("bob", null)
    advanceUntilIdle()
    assertEquals(SettlementUiState.Unavailable, vm.state.value)
    assertFalse(vm.settle(Debt("alice", "bob", 1500), 1))
    session.value = AuthSession("alice", null)
    advanceUntilIdle()
    assertEquals(payment, ready(vm).submission.operation)
    assertEquals(SettlementPhase.FAILED, ready(vm).submission.phase)
    assertEquals(1500L, remaining(vm))
  }

  @Test
  fun mismatchedAcknowledgementAndConflictingLedgerRecordFailSafely() = runTest {
    val vm = create()
    advanceUntilIdle()
    vm.settle(debt(vm), 600)
    advanceUntilIdle()
    val payment = requests.single()
    answer.complete(payment.copy(amountCents = 1))
    advanceUntilIdle()
    assertEquals(SettlementPhase.FAILED, ready(vm).submission.phase)
    assertEquals(1500L, remaining(vm))
    ledger.value = snapshot(payment.copy(amountCents = 1))
    advanceUntilIdle()
    assertEquals(SettlementUiState.Error, vm.state.value)
  }
}
