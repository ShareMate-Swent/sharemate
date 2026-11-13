// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.receipts

import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.item.*
import com.android.sharemate.model.staging.InventorySubmissionStore
import com.android.sharemate.model.staging.StagedItem
import com.android.sharemate.model.text.TextLimits
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InventorySaveViewModelTest {
  @Test
  fun oversizedScannedDraftSurvivesRecoveryAndCanBeReducedBeforeConfirmation() = runTest {
    val scanned = List(InventorySubmission.MAX_ITEMS + 1) { StagedItem("$it", "Milk", 2, 480) }
    val vm = model()
    runCurrent()
    assertTrue(vm.beginNewScan(scanned))
    owners.first().clear()
    val restored = model()
    runCurrent()
    assertEquals(scanned, restored.state.value.submission?.items)
    assertEquals(InventorySavePhase.READY, restored.state.value.phase)
    assertFalse(restored.confirm(scanned))
    assertTrue(repository.calls.isEmpty())
    val corrected = scanned.dropLast(1)
    assertTrue(restored.confirm(corrected))
    runCurrent()
    assertEquals(corrected, repository.calls.single().items)
    assertFalse(repository.calls.single().draft)
  }

  private val owners = mutableListOf<ViewModelStore>()
  private val rows =
      listOf(StagedItem("milk", "Corrected milk", 2, 480), StagedItem("bread", "Bread", 1, null))
  private val repository = SaveRepository()
  private val store = Store()

  @Before
  fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
  }

  @After
  fun tearDown() {
    owners.forEach(ViewModelStore::clear)
    Dispatchers.resetMain()
  }

  private fun model(canSave: Boolean = true, uid: String = "owner", home: String = "home") =
      InventorySaveViewModel(repository, store, uid, home, canSave).also { vm ->
        ViewModelStore().also {
          owners += it
          it.put("save", vm)
        }
      }

  @Test
  fun confirmationRetainsCorrectionsBeforeQueueAndOnlyServerAckMeansSuccess() = runTest {
    val vm = model()
    runCurrent()
    assertEquals(InventorySavePhase.READY, vm.state.value.phase)
    vm.confirm(rows)
    assertEquals(InventorySavePhase.SUBMITTING, vm.state.value.phase)
    vm.confirm(rows)
    runCurrent()
    assertEquals(InventorySavePhase.PENDING, vm.state.value.phase)
    val sent = repository.calls.single()
    assertEquals(sent, store.saved)
    assertEquals(rows, sent.items)
    assertEquals("owner", sent.ownerId)
    assertEquals("home", sent.householdId)
    assertEquals(480L, sent.items.first().priceCents)
    vm.confirm(rows)
    vm.retry()
    vm.beginNewScan(rows)
    runCurrent()
    assertEquals(1, repository.calls.size)
    assertFalse(vm.state.value.canStartScan)
    repository.ack.complete(Result.success(Unit))
    runCurrent()
    assertEquals(InventorySavePhase.CONFIRMED, vm.state.value.phase)
    vm.confirm(rows)
    assertEquals(1, repository.calls.size)
  }

  @Test
  fun durableRecoveryObservesWithoutReinsertingAndMissingServerMarkerAllowsStableRetry() = runTest {
    val vm = model()
    runCurrent()
    vm.confirm(rows)
    runCurrent()
    val first = repository.calls.single()
    owners.first().clear()
    val restored = model()
    runCurrent()
    assertEquals(InventorySavePhase.PENDING, restored.state.value.phase)
    assertEquals(first, restored.state.value.submission)
    assertEquals(1, repository.calls.size)
    repository.status.value = InventorySubmissionStatus.MISSING
    runCurrent()
    assertEquals(InventorySavePhase.FAILED, restored.state.value.phase)
    repository.status.value = InventorySubmissionStatus.PENDING
    restored.retry()
    runCurrent()
    assertEquals(listOf(first, first), repository.calls)
    repository.status.value = InventorySubmissionStatus.CONFIRMED
    runCurrent()
    assertEquals(InventorySavePhase.CONFIRMED, restored.state.value.phase)
    assertEquals(rows, store.saved?.items)
  }

  @Test
  fun storageFailureLeavesCorrectionsAndDoesNotSubmitAndRetryKeepsIdentity() = runTest {
    store.saveFailure = IOException("disk failure")
    val vm = model()
    runCurrent()
    vm.confirm(rows)
    runCurrent()
    assertEquals(InventorySavePhase.FAILED, vm.state.value.phase)
    assertEquals(rows, vm.state.value.submission?.items)
    assertTrue(repository.calls.isEmpty())
    val identity = vm.state.value.submission?.id
    store.saveFailure = null
    vm.retry()
    runCurrent()
    assertEquals(identity, repository.calls.single().id)
  }

  @Test
  fun invalidListsAndPrivateFridgesDoNotWrite() = runTest {
    val privateVm = model(canSave = false)
    val vm = model()
    runCurrent()
    privateVm.confirm(rows)
    for (invalid in
        listOf(
            emptyList(),
            listOf(rows.first(), rows.first()),
            List(InventorySubmission.MAX_ITEMS + 1) { rows.first().copy(id = "$it") })) {
      vm.confirm(invalid)
      runCurrent()
      assertEquals(InventorySavePhase.FAILED, vm.state.value.phase)
    }
    assertTrue(repository.calls.isEmpty())
    assertNull(store.saved)
  }

  @Test
  fun serverRejectionKeepsRetainedRowsForRetry() = runTest {
    val vm = model()
    runCurrent()
    vm.confirm(rows)
    runCurrent()
    repository.ack.complete(Result.failure(IOException("permission denied")))
    repository.status.value = InventorySubmissionStatus.MISSING
    runCurrent()
    assertEquals(InventorySavePhase.FAILED, vm.state.value.phase)
    assertEquals(rows, vm.state.value.submission?.items)
    assertEquals(rows, store.saved?.items)
  }

  @Test
  fun clearingOldOwnerPreventsAckOrObservationUpdatingAnotherSession() = runTest {
    val old = model()
    runCurrent()
    old.confirm(rows)
    runCurrent()
    owners.first().clear()
    val other = model(uid = "other", home = "elsewhere")
    runCurrent()
    repository.ack.complete(Result.success(Unit))
    repository.status.value = InventorySubmissionStatus.CONFIRMED
    runCurrent()
    assertEquals(InventorySavePhase.READY, other.state.value.phase)
    assertNull(other.state.value.submission)
    assertEquals(1, repository.calls.size)
  }

  @Test
  fun confirmedRecoveryCanStartSeparateScanWithNewIdentity() = runTest {
    val old = InventorySubmission("12345678-1234-1234-1234-123456789abc", "owner", "home", rows)
    store.saved = old
    repository.status.value = InventorySubmissionStatus.CONFIRMED
    val vm = model()
    runCurrent()
    assertEquals(InventorySavePhase.CONFIRMED, vm.state.value.phase)
    vm.beginNewScan(rows)
    vm.confirm(listOf(rows.first()))
    runCurrent()
    assertNotEquals(old.id, repository.calls.single().id)
    assertEquals(listOf(rows.first()), store.saved?.items)
  }

  @Test
  fun unreadableJournalCannotBeOverwrittenByANewConfirmation() = runTest {
    val retained =
        InventorySubmission("12345678-1234-1234-1234-123456789abc", "owner", "home", rows)
    store.saved = retained
    store.loadFailure = IOException("unreadable journal")
    val vm = model()
    runCurrent()
    assertFalse(vm.state.value.canConfirm)
    assertFalse(vm.state.value.canStartScan)
    vm.confirm(listOf(rows.first()))
    vm.beginNewScan(rows)
    vm.retry()
    runCurrent()
    assertTrue(repository.calls.isEmpty())
    assertEquals(retained, store.saved)
  }

  @Test
  fun lateAcknowledgementFromPreviousAttemptCannotConfirmANewerAttempt() = runTest {
    val vm = model()
    runCurrent()
    vm.confirm(rows)
    runCurrent()
    val previousAck = repository.ack
    repository.status.value = InventorySubmissionStatus.MISSING
    runCurrent()
    assertEquals(InventorySavePhase.FAILED, vm.state.value.phase)
    repository.status.value = InventorySubmissionStatus.PENDING
    repository.ack = CompletableDeferred()
    vm.retry()
    runCurrent()
    assertEquals(InventorySavePhase.PENDING, vm.state.value.phase)
    previousAck.complete(Result.success(Unit))
    runCurrent()
    assertEquals(InventorySavePhase.PENDING, vm.state.value.phase)
    assertFalse(repository.ack.isCompleted)
  }

  @Test
  fun replacementDraftSurvivesRecreationWithoutRestoringPreviouslyConfirmedReceipt() = runTest {
    val old = InventorySubmission("12345678-1234-1234-1234-123456789abc", "owner", "home", rows)
    store.saved = old
    repository.status.value = InventorySubmissionStatus.CONFIRMED
    val vm = model()
    runCurrent()
    val replacement = listOf(StagedItem("new", "New receipt", 3, 960))
    vm.beginNewScan(replacement)
    owners.first().clear()
    val restored = model()
    runCurrent()
    assertEquals(InventorySavePhase.READY, restored.state.value.phase)
    assertEquals(replacement, restored.state.value.submission?.items)
    assertNotEquals(old.id, restored.state.value.submission?.id)
    assertTrue(repository.calls.isEmpty())
    repository.status.value = InventorySubmissionStatus.PENDING
    restored.confirm(replacement)
    runCurrent()
    assertEquals(replacement, repository.calls.single().items)
  }

  @Test
  fun invalidCorrectionAfterFailedSubmissionIsRejectedWithoutAnotherWrite() = runTest {
    val vm = model()
    runCurrent()
    vm.confirm(rows)
    runCurrent()
    repository.status.value = InventorySubmissionStatus.MISSING
    runCurrent()
    // The staging form cuts typed names, so only a row from an older draft can be too long.
    val tooLong =
        rows.mapIndexed { i, row ->
          if (i == 0) row.copy(name = "a".repeat(TextLimits.ITEM_NAME + 1)) else row
        }
    val staging = com.android.sharemate.ui.staging.StagingViewModel(tooLong)
    val corrected = checkNotNull(staging.confirmedItems())
    assertFalse(vm.confirm(corrected))
    runCurrent()
    assertEquals(corrected, staging.state.value.items)
    assertEquals(1, repository.calls.size)
    vm.retry()
    runCurrent()
    assertEquals(1, repository.calls.size)
    staging.startEditing(rows.first().id)
    staging.updateDraftName("Valid correction")
    staging.saveDraft()
    repository.status.value = InventorySubmissionStatus.PENDING
    assertTrue(vm.confirm(checkNotNull(staging.confirmedItems())))
    runCurrent()
    assertEquals("Valid correction", repository.calls.last().items.first().name)
  }

  @Test
  fun failedObservationRetriesOnlyTheListenerAndCannotDowngradeServerConfirmation() = runTest {
    val retained =
        InventorySubmission("12345678-1234-1234-1234-123456789abc", "owner", "home", rows)
    store.saved = retained
    repository.observation = flow { throw IOException("listener disconnected") }
    val vm = model()
    runCurrent()
    assertEquals(InventorySavePhase.RECOVERY_FAILED, vm.state.value.phase)
    assertEquals(retained, vm.state.value.submission)
    repository.observation = repository.status
    vm.retry()
    runCurrent()
    assertEquals(InventorySavePhase.PENDING, vm.state.value.phase)
    assertTrue(repository.calls.isEmpty())
    repository.status.value = InventorySubmissionStatus.CONFIRMED
    runCurrent()
    repository.status.value = InventorySubmissionStatus.MISSING
    runCurrent()
    assertEquals(InventorySavePhase.CONFIRMED, vm.state.value.phase)
    assertEquals(retained, store.saved)
  }

  @Test
  fun failedNewDraftCannotReplaceDurableReceiptAndRecoveryReloadsTheJournal() = runTest {
    val retained =
        InventorySubmission("12345678-1234-1234-1234-123456789abc", "owner", "home", rows)
    val replacement = listOf(StagedItem("new", "New receipt", 1, 150))
    store.saved = retained
    repository.status.value = InventorySubmissionStatus.CONFIRMED
    val vm = model()
    runCurrent()
    assertEquals(InventorySavePhase.CONFIRMED, vm.state.value.phase)
    store.saveFailure = IOException("disk full")
    assertTrue(runCatching { vm.beginNewScan(replacement) }.exceptionOrNull() is IOException)
    assertEquals(InventorySavePhase.RECOVERY_FAILED, vm.state.value.phase)
    assertEquals(retained, store.saved)
    assertFalse(vm.confirm(replacement))
    store.saveFailure = null
    vm.retry()
    runCurrent()
    assertEquals(InventorySavePhase.CONFIRMED, vm.state.value.phase)
    assertEquals(retained, vm.state.value.submission)
    assertTrue(vm.beginNewScan(replacement))
    assertEquals(InventorySavePhase.READY, vm.state.value.phase)
    assertEquals(replacement, store.saved?.items)
    assertNotEquals(retained.id, store.saved?.id)
    assertTrue(repository.calls.isEmpty())
  }

  @Test
  fun cancelledJournalRecoveryDoesNotReportFailureOrEnableSubmission() = runTest {
    store.loadFailure = CancellationException("owner cancelled")
    val vm = model()
    runCurrent()
    assertEquals(InventorySavePhase.LOADING, vm.state.value.phase)
    assertFalse(vm.confirm(rows))
    assertTrue(repository.calls.isEmpty())
  }

  private class Store : InventorySubmissionStore {
    var saved: InventorySubmission? = null
    var saveFailure: Exception? = null
    var loadFailure: Exception? = null

    override suspend fun load(ownerId: String, householdId: String): InventorySubmission? {
      loadFailure?.let { throw it }
      return saved?.takeIf { it.ownerId == ownerId && it.householdId == householdId }
    }

    override suspend fun save(submission: InventorySubmission) {
      saveFailure?.let { throw it }
      saved = submission
    }
  }

  private class SaveRepository : ItemRepository by FakeItemRepository() {
    val calls = mutableListOf<InventorySubmission>()
    val status = MutableStateFlow(InventorySubmissionStatus.PENDING)
    var observation: Flow<InventorySubmissionStatus> = status
    var ack = CompletableDeferred<Result<Unit>>()

    override fun addItems(submission: InventorySubmission): ItemWrite {
      calls += submission
      return ItemWrite.Queued(ack)
    }

    override fun observeSubmission(
        submission: InventorySubmission
    ): Flow<InventorySubmissionStatus> = observation
  }
}
