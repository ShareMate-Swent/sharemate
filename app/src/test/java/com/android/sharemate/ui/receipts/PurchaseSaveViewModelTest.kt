// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.finance.*
import com.android.sharemate.model.item.*
import com.android.sharemate.model.staging.InventorySubmissionStore
import com.android.sharemate.model.staging.StagedItem
import com.android.sharemate.ui.staging.ReviewedPurchase
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseSaveViewModelTest {
  @Test
  fun journalRejectsInconsistentPurchasePayloadAndProgress() {
    val id = "12345678-1234-1234-1234-123456789abc"
    val expense = SharedExpense(id, "home", "alice", 1000, purchase.shares)
    val invalid =
        listOf<() -> InventorySubmission>(
            { InventorySubmission(id, "alice", "other", rows, expense = expense) },
            { InventorySubmission(id, "bob", "home", rows, expense = expense) },
            {
              InventorySubmission(
                  java.util.UUID.randomUUID().toString(), "alice", "home", rows, expense = expense)
            },
            { InventorySubmission(id, "alice", "home", rows, draft = true, expense = expense) },
            {
              InventorySubmission(
                  id, "alice", "home", rows.map { it.copy(priceCents = null) }, expense = expense)
            },
            {
              InventorySubmission(
                  id, "alice", "home", rows.map { it.copy(priceCents = 999) }, expense = expense)
            },
            {
              InventorySubmission(
                  id, "alice", "home", rows.map { it.copy(priceCents = 1001) }, expense = expense)
            },
            {
              InventorySubmission(
                  id,
                  "alice",
                  "home",
                  rows,
                  expense =
                      SharedExpense(
                          id, "home", "alice", 100_000_001, mapOf("alice" to 100_000_001L)))
            },
            {
              InventorySubmission(
                  id,
                  "alice",
                  "home",
                  rows,
                  expense = expense,
                  expenseProgress = ExpenseProgress.CONFIRMED)
            },
            { InventorySubmission(id, "alice", "home", rows, inventoryConfirmed = true) })
    invalid.forEach { assertTrue(runCatching(it).exceptionOrNull() is IllegalArgumentException) }
  }

  private val owners = mutableListOf<ViewModelStore>()
  private val rows = listOf(StagedItem("milk", "Edited milk", 2, 1000))
  private val members = mapOf("alice" to "Alice", "bob" to "Bob")
  private val purchase =
      ReviewedPurchase(rows, "alice", 1000L, mapOf("alice" to 500L, "bob" to 500L))
  private val store = Store()
  private val inventory = Inventory()
  private val expenses = Expenses()

  @Test
  fun unknownOutcomeBackoffCapsAtOneMinuteAndManualRetryWakesIt() = runTest {
    expenses.failure = IOException("response lost")
    val vm = submit()
    confirmInventory()
    var calls = 1
    for (delay in listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L)) {
      advanceTimeBy(delay - 1)
      runCurrent()
      assertEquals(calls, expenses.calls.size)
      advanceTimeBy(1)
      runCurrent()
      assertEquals(++calls, expenses.calls.size)
    }
    val immutable = expenses.calls.first()
    assertTrue(expenses.calls.all { it == immutable })
    expenses.failure = null
    expenses.result.complete(Unit)
    vm.retry()
    runCurrent()
    assertEquals(++calls, expenses.calls.size)
    assertEquals(InventorySavePhase.CONFIRMED, vm.state.value.phase)
    vm.retry()
    runCurrent()
    assertEquals(calls, expenses.calls.size)
    assertEquals(1, inventory.calls.size)
  }

  @Test
  fun pendingInventoryRetryWaitsAndMissingInventoryRetriesTheSamePurchase() = runTest {
    val vm = submit()
    val retained = inventory.calls.single()
    vm.retry()
    runCurrent()
    assertEquals(listOf(retained), inventory.calls)
    assertTrue(expenses.calls.isEmpty())
    inventory.status.value = InventorySubmissionStatus.MISSING
    runCurrent()
    assertEquals(InventorySavePhase.FAILED, vm.state.value.phase)
    inventory.status.value = InventorySubmissionStatus.PENDING
    vm.retry()
    runCurrent()
    assertEquals(listOf(retained, retained), inventory.calls)
    confirmInventory()
    expenses.result.complete(Unit)
    runCurrent()
    assertEquals(listOf(retained.expense), expenses.calls)
    assertEquals(InventorySavePhase.CONFIRMED, vm.state.value.phase)
  }

  @Test
  fun retryCannotOvertakeAnInFlightJournalCommit() = runTest {
    expenses.failure = IOException("response lost")
    val vm = submit()
    confirmInventory()
    val commit = CompletableDeferred<Unit>()
    store.blockProgress = commit
    advanceTimeBy(5_000)
    runCurrent()
    expenses.failure = null
    expenses.result.complete(Unit)
    vm.retry()
    runCurrent()
    try {
      assertEquals(1, expenses.calls.size)
    } finally {
      commit.complete(Unit)
      runCurrent()
    }
    assertEquals(2, expenses.calls.size)
    assertEquals(ExpenseProgress.CONFIRMED, store.saved?.expenseProgress)
  }

  @Before
  fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
  }

  @After
  fun tearDown() {
    owners.forEach(ViewModelStore::clear)
    Dispatchers.resetMain()
  }

  private fun TestScope.model(
      canSave: Boolean = true,
      uid: String = "alice",
      home: String = "home"
  ) =
      InventorySaveViewModel(inventory, store, uid, home, canSave, expenses).also { vm ->
        ViewModelStore().also {
          owners += it
          it.put("save", vm)
        }
        runCurrent()
      }

  private fun TestScope.submit() =
      model().also {
        assertTrue(it.confirmPurchase(purchase, members))
        runCurrent()
      }

  private fun TestScope.confirmInventory() {
    inventory.status.value = InventorySubmissionStatus.CONFIRMED
    runCurrent()
  }

  private fun TestScope.restart(): InventorySaveViewModel {
    owners.last().clear()
    return model()
  }

  @Test
  fun inventoryMustConfirmBeforeExpenseAndDuplicateTapsCannotChangePayload() = runTest {
    val vm = model()
    assertTrue(vm.confirmPurchase(purchase, members))
    assertFalse(vm.confirmPurchase(purchase, members))
    runCurrent()
    val submitted = inventory.calls.single()
    assertEquals(submitted, store.saved)
    assertEquals(rows, submitted.items)
    assertEquals(submitted.id, submitted.expense?.id)
    assertTrue(expenses.calls.isEmpty())
    assertEquals(InventorySavePhase.PENDING, vm.state.value.phase)
    assertFalse(vm.state.value.canStartScan)
    inventory.ack.complete(Result.success(Unit))
    runCurrent()
    assertEquals(submitted.expense, expenses.calls.single())
    assertEquals(ExpenseProgress.UNKNOWN, store.saved?.expenseProgress)
    assertTrue(store.saved?.inventoryConfirmed == true)
    assertFalse(vm.confirm(rows))
    expenses.result.complete(Unit)
    runCurrent()
    assertEquals(InventorySavePhase.CONFIRMED, vm.state.value.phase)
    assertEquals(ExpenseProgress.CONFIRMED, store.saved?.expenseProgress)
    assertTrue(vm.state.value.canStartScan)
    assertEquals(1, inventory.calls.size)
  }

  @Test
  fun rejectionSurvivesLateInventoryAckAndRestartUntilExplicitRetry() = runTest {
    expenses.failure =
        ExpenseRejectedException(ExpenseRejectionReason.PermissionDenied, IOException())
    val vm = submit()
    confirmInventory()
    assertEquals(ExpenseProgress.REJECTED, store.saved?.expenseProgress)
    inventory.ack.complete(Result.success(Unit))
    runCurrent()
    assertEquals(ExpenseProgress.REJECTED, vm.state.value.submission?.expenseProgress)
    assertEquals(1, expenses.calls.size)
    advanceTimeBy(60_000)
    runCurrent()
    assertEquals(1, expenses.calls.size)
    val retained = store.saved
    val restored = restart()
    assertEquals(retained, restored.state.value.submission)
    assertEquals(ExpenseProgress.REJECTED, restored.state.value.submission?.expenseProgress)
    assertEquals(1, expenses.calls.size)
    expenses.failure = null
    expenses.result.complete(Unit)
    restored.retry()
    runCurrent()
    assertEquals(InventorySavePhase.CONFIRMED, restored.state.value.phase)
    assertEquals(listOf(retained?.expense, retained?.expense), expenses.calls)
    assertEquals(1, inventory.calls.size)
  }

  @Test
  fun diskAndTransportFailuresRetainPayloadAndRetryOnlyTheMissingPhase() = runTest {
    store.failure = IOException("disk full")
    val vm = submit()
    assertEquals(InventorySavePhase.FAILED, vm.state.value.phase)
    assertTrue(inventory.calls.isEmpty())
    assertTrue(expenses.calls.isEmpty())
    val identity = vm.state.value.submission?.id
    store.failure = null
    vm.retry()
    runCurrent()
    assertEquals(identity, inventory.calls.single().id)
    assertEquals(purchase.shares, store.saved?.expense?.shares)
    val retained = store.saved
    store.failure = IOException("journal unavailable")
    confirmInventory()
    assertEquals(retained, store.saved)
    assertEquals(ExpenseProgress.UNKNOWN, vm.state.value.submission?.expenseProgress)
    assertTrue(expenses.calls.isEmpty())
    store.failure = null
    expenses.failure = IOException("response lost")
    advanceTimeBy(5_000)
    runCurrent()
    assertEquals(retained?.expense, expenses.calls.single())
    assertEquals(ExpenseProgress.UNKNOWN, vm.state.value.submission?.expenseProgress)
    assertEquals(InventorySavePhase.PENDING, vm.state.value.phase)
    assertFalse(vm.confirmPurchase(purchase.copy(totalCents = 2000), members))
    expenses.failure = null
    expenses.result.complete(Unit)
    advanceTimeBy(10_000)
    runCurrent()
    assertEquals(listOf(retained?.expense, retained?.expense), expenses.calls)
    assertEquals(ExpenseProgress.CONFIRMED, store.saved?.expenseProgress)
    assertEquals(1, inventory.calls.size)
    assertEquals(InventorySavePhase.CONFIRMED, vm.state.value.phase)
  }

  @Test
  fun restartRecoversMissingFinancialPhaseWithoutAnotherInventoryWrite() = runTest {
    store.saved = InventorySubmission("12345678-1234-1234-1234-123456789abc", "alice", "home", rows)
    inventory.status.value = InventorySubmissionStatus.CONFIRMED
    val vm = model()
    assertEquals(InventorySavePhase.CONFIRMED, vm.state.value.phase)
    assertNull(vm.state.value.submission?.expense)
    assertTrue(expenses.calls.isEmpty())
    assertTrue(inventory.calls.isEmpty())
    assertTrue(vm.beginNewScan(rows))
    inventory.status.value = InventorySubmissionStatus.PENDING
    assertTrue(vm.confirmPurchase(purchase, members))
    runCurrent()
    val identity = store.saved?.id
    val pending = restart()
    assertEquals(InventorySavePhase.PENDING, pending.state.value.phase)
    assertEquals(identity, pending.state.value.submission?.id)
    assertEquals(1, inventory.calls.size)
    assertTrue(expenses.calls.isEmpty())
    confirmInventory()
    val recovered = restart()
    assertEquals(1, inventory.calls.size)
    assertEquals(2, expenses.calls.size)
    assertTrue(expenses.calls.all { it.id == identity })
    expenses.result.complete(Unit)
    runCurrent()
    assertEquals(InventorySavePhase.CONFIRMED, recovered.state.value.phase)
    val confirmed = restart()
    assertEquals(InventorySavePhase.CONFIRMED, confirmed.state.value.phase)
    assertEquals(2, expenses.calls.size)
  }

  @Test
  fun privateInvalidAndChangedScopesCannotSubmitAnotherPurchase() = runTest {
    val privateVm = model(canSave = false)
    val vm = model()
    assertFalse(privateVm.confirmPurchase(purchase, members))
    assertFalse(vm.confirmPurchase(purchase, members - "bob"))
    assertFalse(vm.confirmPurchase(purchase.copy(paidBy = "bob"), members))
    assertFalse(vm.confirmPurchase(purchase.copy(totalCents = 999), members))
    assertFalse(
        vm.confirmPurchase(purchase.copy(shares = mapOf("alice" to 499L, "bob" to 501L)), members))
    assertTrue(inventory.calls.isEmpty())
    assertTrue(expenses.calls.isEmpty())
    assertTrue(vm.confirmPurchase(purchase, members))
    runCurrent()
    owners.forEach(ViewModelStore::clear)
    val otherAccount = model(uid = "bob")
    val otherHousehold = model(home = "elsewhere")
    inventory.ack.complete(Result.success(Unit))
    confirmInventory()
    assertNull(otherAccount.state.value.submission)
    assertNull(otherHousehold.state.value.submission)
    assertEquals(InventorySavePhase.READY, otherAccount.state.value.phase)
    assertTrue(expenses.calls.isEmpty())
  }

  private class Store : InventorySubmissionStore {
    var blockProgress: CompletableDeferred<Unit>? = null
    var saved: InventorySubmission? = null
    var failure: Exception? = null

    override suspend fun load(ownerId: String, householdId: String) =
        saved?.takeIf { it.ownerId == ownerId && it.householdId == householdId }

    override suspend fun save(submission: InventorySubmission) {
      blockProgress?.let { gate ->
        blockProgress = null
        withContext(NonCancellable) {
          gate.await()
          saved = submission
        }
        return
      }
      failure?.let { throw it }
      saved = submission
    }
  }

  private class Inventory : ItemRepository by FakeItemRepository() {
    val calls = mutableListOf<InventorySubmission>()
    val status = MutableStateFlow(InventorySubmissionStatus.PENDING)
    val ack = CompletableDeferred<Result<Unit>>()

    override fun addItems(submission: InventorySubmission): ItemWrite {
      calls += submission
      return ItemWrite.Queued(ack)
    }

    override fun observeSubmission(
        submission: InventorySubmission
    ): Flow<InventorySubmissionStatus> = status
  }

  private class Expenses : ExpenseSubmissionRepository {
    val calls = mutableListOf<SharedExpense>()
    val result = CompletableDeferred<Unit>()
    var failure: Exception? = null

    override suspend fun recordExpense(expense: SharedExpense): SharedExpense {
      calls += expense
      failure?.let { throw it }
      result.await()
      return expense
    }
  }
}
