// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.finance.*
import com.android.sharemate.model.item.*
import com.android.sharemate.model.staging.InventorySubmissionStore
import com.android.sharemate.model.staging.ReceiptCorrection
import com.android.sharemate.model.staging.StagedItem
import com.android.sharemate.ui.staging.ReviewedPurchase
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseSaveViewModelTest {
  private fun savedOriginal() =
      InventorySubmission(
          "12345678-1234-1234-1234-123456789abc",
          "alice",
          "home",
          rows,
          expense =
              SharedExpense(
                  "12345678-1234-1234-1234-123456789abc", "home", "alice", 1000, purchase.shares),
          inventoryConfirmed = true,
          expenseProgress = ExpenseProgress.CONFIRMED)

  @Test
  fun correctionRetryPreservesIdentityAndTitleAfterReadFailureAndCancellation() = runTest {
    store.saved = savedOriginal()
    store.metadata =
        com.android.sharemate.model.staging.ReceiptDetails(
            store.saved!!.id, "alice", "home", "photo", 1, title = "Original title")
    val vm = model()
    store.metadata = store.metadata!!.copy(title = "Changed remotely after opening")
    expenses.correctionFailure = IOException("Offline")
    assertTrue(vm.correctPurchase(purchase, members, "  Groceries  "))
    vm.retry()
    runCurrent()
    val correction = store.correction
    assertEquals("Original title", correction?.originalTitle)
    assertEquals(InventorySavePhase.FAILED, vm.state.value.phase)
    store.correctionReadFailure = IOException("Storage unavailable")
    vm.retry()
    runCurrent()
    assertEquals(InventorySavePhase.FAILED, vm.state.value.phase)
    assertTrue(vm.state.value.correctionPending)
    store.correctionReadFailure = CancellationException("Stopped")
    vm.retry()
    runCurrent()
    assertEquals(correction, store.correction)
    store.correctionReadFailure = null
    expenses.correctionFailure = CancellationException("Stopped")
    vm.retry()
    vm.retry()
    runCurrent()
    assertTrue(vm.state.value.correctionPending)
    expenses.correctionFailure = null
    store.correction = null
    vm.retry()
    runCurrent()
    assertEquals(InventorySavePhase.CONFIRMED, vm.state.value.phase)
    assertEquals("Groceries", store.metadata?.title)
    assertTrue(expenses.corrections.all { it == correction })
    assertTrue(inventory.calls.isEmpty())
    assertTrue(vm.correctPurchase(purchase, members, "Second title"))
    runCurrent()
    assertEquals("Groceries", expenses.corrections.last().originalTitle)
  }

  @Test
  fun malformedCorrectionsCannotReachTheBackendOrOverwriteTheJournal() = runTest {
    assertFalse(model().correctPurchase(purchase, members, ""))
    store.saved = savedOriginal()
    val vm = model()
    val tooMany = List(101) { StagedItem("row-$it", "Milk", 1, 1) }
    val invalid =
        listOf(
            purchase.copy(paidBy = "stranger"),
            purchase.copy(totalCents = 999),
            purchase.copy(shares = mapOf("alice" to 1000L, "bob" to 0L)),
            purchase.copy(
                items = tooMany, totalCents = 101, shares = mapOf("alice" to 51L, "bob" to 50L)))
    for (value in invalid) assertFalse(vm.correctPurchase(value, members, ""))
    assertTrue(expenses.corrections.isEmpty())
    assertNull(store.correction)
    store.saved = InventorySubmission(store.saved!!.id, "alice", "home", rows, draft = true)
    assertFalse(model().correctPurchase(purchase, members, ""))
  }

  @Test
  fun correctionRetriesSamePayloadAfterRestartWithoutAnotherInventoryInsert() = runTest {
    store.saved = savedOriginal()
    val vm = model()
    expenses.correctionFailure = IOException("response lost")
    val edited =
        purchase.copy(
            items = listOf(rows[0].copy(name = "Oat milk", priceCents = 1200)),
            totalCents = 1200,
            shares = mapOf("alice" to 600L, "bob" to 600L))
    assertTrue(vm.correctPurchase(edited, members, "Courses"))
    runCurrent()
    assertEquals(InventorySavePhase.FAILED, vm.state.value.phase)
    val retained = store.correction
    assertNotNull(retained)
    assertFalse(vm.correctPurchase(edited, members, "Another edit"))
    expenses.correctionFailure = null
    val recovered = restart()
    runCurrent()
    assertEquals(InventorySavePhase.CONFIRMED, recovered.state.value.phase)
    assertEquals(listOf(retained, retained), expenses.corrections)
    assertEquals("Oat milk", store.saved?.items?.single()?.name)
    assertNull(store.correction)
    assertTrue(inventory.calls.isEmpty())
    assertTrue(expenses.calls.isEmpty())
  }

  @Test
  fun anotherMemberCanPayWhileJournalOwnershipStaysWithSubmitter() = runTest {
    val vm = model()
    assertTrue(vm.confirmPurchase(purchase.copy(paidBy = "bob"), members))
    runCurrent()
    assertEquals("alice", store.saved?.ownerId)
    assertEquals("bob", store.saved?.expense?.paidBy)
    confirmInventory()
    expenses.result.complete(Unit)
    runCurrent()
    assertEquals("bob", expenses.calls.single().paidBy)
    assertEquals(InventorySavePhase.CONFIRMED, vm.state.value.phase)
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
  fun persistentRejectionCanBeDeferredAcrossRestartAndRecoveredWithOriginalIdentity() = runTest {
    expenses.failure =
        ExpenseRejectedException(ExpenseRejectionReason.PermissionDenied, IOException())
    submit()
    confirmInventory()
    val retained = store.saved
    val restored = restart()
    restored.setAside()
    runCurrent()
    assertTrue(restored.state.value.canStartScan)
    assertNull(store.saved)
    assertEquals(listOf(retained), restored.deferred.value)
    val next = restart()
    assertTrue(next.state.value.canStartScan)
    assertEquals(listOf(retained), next.deferred.value)
    next.recoverDeferred()
    runCurrent()
    assertEquals(retained, next.state.value.submission)
    assertEquals(1, expenses.calls.size)
    next.retry()
    runCurrent()
    assertEquals(listOf(retained?.expense, retained?.expense), expenses.calls)
    assertEquals(1, inventory.calls.size)
    assertFalse(next.state.value.canStartScan)
    next.setAside()
    runCurrent()
    assertTrue(next.beginNewScan(rows))
    assertNotEquals(retained?.id, next.state.value.submission?.id)
    assertEquals(listOf(retained), next.deferred.value)
  }

  @Test
  fun setAsideWaitsForInFlightCommitBeforeReleasingTheSlot() = runTest {
    expenses.failure =
        ExpenseRejectedException(ExpenseRejectionReason.PermissionDenied, IOException())
    val vm = submit()
    confirmInventory()
    val retained = store.saved
    val commit = CompletableDeferred<Unit>()
    store.blockProgress = commit
    vm.retry()
    runCurrent()
    vm.setAside()
    runCurrent()
    assertFalse(vm.state.value.canStartScan)
    assertTrue(store.archived.isEmpty())
    commit.complete(Unit)
    runCurrent()
    assertTrue(vm.state.value.canStartScan)
    assertNull(store.saved)
    assertEquals(listOf(retained), store.archived)
    assertEquals(1, expenses.calls.size)
  }

  @Test
  fun anyDeferredReceiptCanBeRecoveredByIdentity() = runTest {
    expenses.failure =
        ExpenseRejectedException(ExpenseRejectionReason.PermissionDenied, IOException())
    val vm = submit()
    confirmInventory()
    val first = checkNotNull(store.saved)
    vm.setAside()
    runCurrent()
    assertTrue(vm.beginNewScan(rows))
    assertTrue(vm.confirmPurchase(purchase, members))
    runCurrent()
    val second = checkNotNull(store.saved)
    assertNotEquals(first.id, second.id)
    vm.setAside()
    runCurrent()
    assertEquals(2, vm.deferred.value.size)
    vm.recoverDeferred(second.id)
    runCurrent()
    assertEquals(second, vm.state.value.submission)
    assertEquals(listOf(first), vm.deferred.value)
  }

  @Test
  fun failedArchiveKeepsOriginalPurchaseAndBlocksNewScans() = runTest {
    expenses.failure =
        ExpenseRejectedException(ExpenseRejectionReason.PermissionDenied, IOException())
    val vm = submit()
    confirmInventory()
    val retained = store.saved
    store.failure = IOException("disk full")
    vm.setAside()
    runCurrent()
    assertFalse(vm.state.value.canStartScan)
    assertEquals(retained, store.saved)
    assertEquals(retained, vm.state.value.submission)
    assertEquals(InventorySavePhase.RECOVERY_FAILED, vm.state.value.phase)
    assertTrue(vm.deferred.value.isEmpty())
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
    assertFalse(vm.confirmPurchase(purchase.copy(paidBy = "outsider"), members))
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

  @Test
  fun unavailableExpenseRepositoryKeepsRecoveredInventoryAndRejectsNewPurchases() = runTest {
    val vm = InventorySaveViewModel(inventory, store, "alice", "home")
    ViewModelStore().also {
      owners += it
      it.put("save", vm)
    }
    runCurrent()
    assertFalse(vm.confirmPurchase(purchase, members))
    owners.last().clear()
    val id = "12345678-1234-1234-1234-123456789abc"
    store.saved =
        InventorySubmission(
            id,
            "alice",
            "home",
            rows,
            expense = SharedExpense(id, "home", "alice", 1000, purchase.shares),
            inventoryConfirmed = true)
    val recovered = InventorySaveViewModel(inventory, store, "alice", "home")
    ViewModelStore().also {
      owners += it
      it.put("save", recovered)
    }
    runCurrent()
    recovered.retry()
    runCurrent()
    assertEquals(InventorySavePhase.PENDING, recovered.state.value.phase)
    assertTrue(checkNotNull(recovered.state.value.submission).inventoryConfirmed)
    assertTrue(inventory.calls.isEmpty())
    assertTrue(expenses.calls.isEmpty())
  }

  @Test
  fun submittingAndReadOnlyRecoveryIgnoreRetriesAndCannotReleasePurchases() = runTest {
    val vm = model()
    store.blockProgress = CompletableDeferred()
    assertTrue(vm.confirmPurchase(purchase, members))
    runCurrent()
    vm.retry()
    vm.setAside()
    vm.recoverDeferred("missing")
    assertEquals(InventorySavePhase.SUBMITTING, vm.state.value.phase)
    val gate = checkNotNull(store.activeCommit)
    gate.complete(Unit)
    runCurrent()
    assertEquals(1, inventory.calls.size)
    confirmInventory()
    val privateVm = model(canSave = false)
    privateVm.retry()
    privateVm.setAside()
    privateVm.recoverDeferred()
    runCurrent()
    assertEquals(1, expenses.calls.size)
    assertTrue(store.archived.isEmpty())
    assertEquals(InventorySavePhase.PENDING, privateVm.state.value.phase)
  }

  @Test
  fun deferredRecoveryFailureRetainsArchiveAndCanBeRetriedAfterJournalReload() = runTest {
    expenses.failure =
        ExpenseRejectedException(ExpenseRejectionReason.PermissionDenied, IOException())
    val vm = submit()
    confirmInventory()
    vm.setAside()
    runCurrent()
    val retained = store.archived.single()
    vm.recoverDeferred("missing")
    assertEquals(InventorySavePhase.READY, vm.state.value.phase)
    store.failure = IOException("disk full")
    vm.recoverDeferred(retained.id)
    runCurrent()
    assertEquals(InventorySavePhase.RECOVERY_FAILED, vm.state.value.phase)
    assertEquals(listOf(retained), store.archived)
    assertNull(store.saved)
    store.failure = null
    vm.retry()
    runCurrent()
    assertEquals(InventorySavePhase.READY, vm.state.value.phase)
    assertTrue(vm.beginNewScan(rows))
    vm.recoverDeferred(retained.id)
    runCurrent()
    assertTrue(checkNotNull(vm.state.value.submission).draft)
    assertEquals(listOf(retained), store.archived)
    assertEquals(1, inventory.calls.size)
  }

  @Test
  fun cancelledDeferredRecoveryDoesNotDiscardArchivedPayload() = runTest {
    expenses.failure =
        ExpenseRejectedException(ExpenseRejectionReason.PermissionDenied, IOException())
    val vm = submit()
    confirmInventory()
    vm.setAside()
    runCurrent()
    val retained = store.archived.single()
    store.failure = CancellationException("owner gone")
    vm.recoverDeferred(retained.id)
    runCurrent()
    assertEquals(listOf(retained), store.archived)
    assertNull(store.saved)
    assertEquals(InventorySavePhase.SUBMITTING, vm.state.value.phase)
    assertEquals(1, expenses.calls.size)
  }

  @Test
  fun inconsistentExpenseResponseStaysUnknownUntilMatchingRetrySucceeds() = runTest {
    expenses.mismatch = true
    val vm = submit()
    confirmInventory()
    expenses.result.complete(Unit)
    runCurrent()
    assertEquals(ExpenseProgress.UNKNOWN, vm.state.value.submission?.expenseProgress)
    assertEquals(InventorySavePhase.PENDING, vm.state.value.phase)
    val retained = store.saved
    expenses.mismatch = false
    vm.retry()
    runCurrent()
    assertEquals(InventorySavePhase.CONFIRMED, vm.state.value.phase)
    assertEquals(listOf(retained?.expense, retained?.expense), expenses.calls)
    assertEquals(1, inventory.calls.size)
  }

  @Test
  fun cancelledRejectionPersistenceLeavesOriginalPayloadForRestart() = runTest {
    expenses.failure =
        ExpenseRejectedException(ExpenseRejectionReason.PermissionDenied, IOException())
    val vm = submit()
    vm.setAside()
    assertTrue(store.archived.isEmpty())
    store.failProgress = ExpenseProgress.REJECTED
    store.failure = CancellationException("owner gone")
    confirmInventory()
    assertEquals(ExpenseProgress.REJECTED, vm.state.value.submission?.expenseProgress)
    assertEquals(ExpenseProgress.UNKNOWN, store.saved?.expenseProgress)
    val immutable = store.saved?.expense
    store.failure = null
    val recovered = restart()
    assertEquals(immutable, recovered.state.value.submission?.expense)
    assertEquals(ExpenseProgress.REJECTED, recovered.state.value.submission?.expenseProgress)
    assertEquals(1, inventory.calls.size)
    assertEquals(listOf(immutable, immutable), expenses.calls)
  }

  @Test
  fun lateInventoryListenerFailureCannotEraseFinancialProgress() = runTest {
    val failure = CompletableDeferred<Unit>()
    inventory.observation = flow {
      emit(InventorySubmissionStatus.PENDING)
      failure.await()
      throw IOException("listener lost")
    }
    val vm = submit()
    inventory.ack.complete(Result.success(Unit))
    runCurrent()
    failure.complete(Unit)
    runCurrent()
    assertEquals(InventorySavePhase.PENDING, vm.state.value.phase)
    assertTrue(checkNotNull(vm.state.value.submission).inventoryConfirmed)
    assertEquals(ExpenseProgress.UNKNOWN, store.saved?.expenseProgress)
    expenses.result.complete(Unit)
    runCurrent()
    assertEquals(InventorySavePhase.CONFIRMED, vm.state.value.phase)
    assertEquals(1, expenses.calls.size)
  }

  private class Store : InventorySubmissionStore {
    var correction: ReceiptCorrection? = null
    var correctionReadFailure: Exception? = null
    var metadata: com.android.sharemate.model.staging.ReceiptDetails? = null

    override suspend fun details(ownerId: String, householdId: String, id: String) = metadata

    override suspend fun saveDetails(details: com.android.sharemate.model.staging.ReceiptDetails) {
      metadata = details
    }

    override suspend fun pendingCorrection(
        ownerId: String,
        householdId: String,
        id: String
    ): ReceiptCorrection? {
      correctionReadFailure?.let { throw it }
      return correction
    }

    override suspend fun saveCorrection(correction: ReceiptCorrection) {
      this.correction = correction
    }

    override suspend fun clearCorrection(ownerId: String, householdId: String, id: String) {
      correction = null
    }

    var activeCommit: CompletableDeferred<Unit>? = null
    var blockProgress: CompletableDeferred<Unit>? = null
    val archived = mutableListOf<InventorySubmission>()

    override suspend fun deferred(ownerId: String, householdId: String) =
        archived.filter { it.ownerId == ownerId && it.householdId == householdId }

    override suspend fun setAside(submission: InventorySubmission) {
      failure?.let { throw it }
      archived += submission
      saved = null
    }

    var failProgress: ExpenseProgress? = null
    var saved: InventorySubmission? = null
    var failure: Exception? = null

    override suspend fun load(ownerId: String, householdId: String) =
        saved?.takeIf { it.ownerId == ownerId && it.householdId == householdId }

    override suspend fun save(submission: InventorySubmission) {
      blockProgress?.let { gate ->
        activeCommit = gate
        blockProgress = null
        withContext(NonCancellable) {
          gate.await()
          saved = submission
        }
        return
      }
      if (failProgress == null || failProgress == submission.expenseProgress)
          failure?.let { throw it }
      archived.removeAll { it.id == submission.id }
      saved = submission
    }
  }

  private class Inventory : ItemRepository by FakeItemRepository() {
    var observation: Flow<InventorySubmissionStatus>? = null
    val calls = mutableListOf<InventorySubmission>()
    val status = MutableStateFlow(InventorySubmissionStatus.PENDING)
    val ack = CompletableDeferred<Result<Unit>>()

    override fun addItems(submission: InventorySubmission): ItemWrite {
      calls += submission
      return ItemWrite.Queued(ack)
    }

    override fun observeSubmission(
        submission: InventorySubmission
    ): Flow<InventorySubmissionStatus> = observation ?: status
  }

  private class Expenses : ExpenseSubmissionRepository {
    val corrections = mutableListOf<ReceiptCorrection>()
    var correctionFailure: Exception? = null

    override suspend fun correctReceipt(correction: ReceiptCorrection) {
      corrections += correction
      correctionFailure?.let { throw it }
    }

    var mismatch = false
    val calls = mutableListOf<SharedExpense>()
    val result = CompletableDeferred<Unit>()
    var failure: Exception? = null

    override suspend fun recordExpense(expense: SharedExpense): SharedExpense {
      calls += expense
      failure?.let { throw it }
      result.await()
      return if (mismatch)
          SharedExpense(
              expense.id, expense.householdId, expense.paidBy, 1001, mapOf("alice" to 1001L))
      else expense
    }
  }
}
