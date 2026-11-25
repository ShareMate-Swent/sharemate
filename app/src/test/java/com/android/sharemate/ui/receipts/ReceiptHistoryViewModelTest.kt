// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.finance.ExpenseSubmissionRepository
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.item.InventorySubmissionStatus
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.item.ItemWrite
import com.android.sharemate.model.receipt.ReceiptPhotoStore
import com.android.sharemate.model.staging.InventorySubmissionStore
import com.android.sharemate.model.staging.ReceiptDetails
import com.android.sharemate.model.staging.StagedItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptHistoryViewModelTest {
  @Test
  fun savedDeletionKeepsLocalDataOnServerFailureAndCanRetry() = runTest {
    val store = MemoryStore()
    val photos = mock(ReceiptPhotoStore::class.java)
    var fail = true
    val expenses =
        object : com.android.sharemate.model.finance.ExpenseSubmissionRepository {
          override suspend fun recordExpense(
              expense: com.android.sharemate.model.finance.SharedExpense
          ) = expense

          override suspend fun deleteReceipt(householdId: String, id: String) {
            if (fail) error("Offline")
          }
        }
    store.save(
        InventorySubmission(
            "00000000-0000-0000-0000-000000000003",
            "owner",
            "home",
            listOf(StagedItem("milk", "Milk", 1, 100))))
    store.saveDetails(
        ReceiptDetails(
            "00000000-0000-0000-0000-000000000003", "owner", "home", "photo", 1, savedAtMillis = 2))
    val history = historyModel(mock(ItemRepository::class.java), store, photos, expenses = expenses)
    owners += ViewModelStore().also { it.put("history", history) }
    runCurrent()
    assertFalse(history.deleteReceipt("00000000-0000-0000-0000-000000000003"))
    assertEquals(1, history.receipts.value.size)
    verify(photos, never()).delete("00000000-0000-0000-0000-000000000003")
    fail = false
    assertTrue(history.deleteReceipt("00000000-0000-0000-0000-000000000003"))
    assertTrue(history.receipts.value.isEmpty())
    assertNull(store.details("owner", "home", "00000000-0000-0000-0000-000000000003"))
    verify(photos).delete("00000000-0000-0000-0000-000000000003")
  }

  private fun historyModel(
      repository: ItemRepository,
      store: InventorySubmissionStore,
      photos: ReceiptPhotoStore,
      expenses: ExpenseSubmissionRepository = mock(ExpenseSubmissionRepository::class.java),
  ) =
      ReceiptHistoryViewModel(
          repository,
          store,
          photos,
          "owner",
          "home",
          expenses,
          ReceiptHistorySync(store, EmptyReceiptCloud, "owner", "home"),
          { 0L })

  private val owners = mutableListOf<ViewModelStore>()

  @Test
  fun explicitDraftSaveKeepsPhotoItemsTitleAndSharingSelection() = runTest {
    val store = MemoryStore()
    val photos = mock(ReceiptPhotoStore::class.java)
    `when`(photos.retain(anyK(), anyK())).thenReturn("retained-photo")
    val repository = mock(ItemRepository::class.java)
    val history = historyModel(repository, store, photos)
    owners += ViewModelStore().also { it.put("history", history) }
    runCurrent()
    val id = history.retainScan("source-photo", emptyList())
    val rows = listOf(StagedItem("milk", "Milk", 2, 290))
    assertTrue(history.editDraft(id, rows, "Courses", "bob", setOf("owner", "bob")))
    history.refresh()
    val draft = history.receipts.value.single()
    assertTrue(draft.submission.draft)
    assertEquals(rows, draft.submission.items)
    assertEquals("retained-photo", draft.details?.photoUri)
    assertEquals("Courses", draft.details?.title)
    assertEquals("bob", draft.details?.draftPaidBy)
    assertEquals(setOf("owner", "bob"), draft.details?.draftParticipants)
    verifyNoInteractions(repository)
  }

  @Test
  fun discardRemovesOnlyDraftAndFailureLeavesItRecoverable() = runTest {
    val store = MemoryStore()
    val photos = mock(ReceiptPhotoStore::class.java)
    val rows = listOf(StagedItem("milk", "Milk", 1, 290))
    val draftId = java.util.UUID.randomUUID().toString()
    val savedId = java.util.UUID.randomUUID().toString()
    store.save(InventorySubmission(draftId, "owner", "home", rows, draft = true))
    store.save(InventorySubmission(savedId, "owner", "home", rows))
    store.saveDetails(ReceiptDetails(savedId, "owner", "home", "saved-photo", 1L, 2L))
    val history = historyModel(mock(ItemRepository::class.java), store, photos)
    owners += ViewModelStore().also { it.put("history", history) }
    runCurrent()
    assertFalse(history.discardDraft(savedId))
    store.failDelete = true
    assertFalse(history.discardDraft(draftId))
    assertTrue(history.error.value)
    assertEquals(2, history.receipts.value.size)
    verifyNoInteractions(photos)
    store.failDelete = false
    assertTrue(history.discardDraft(draftId))
    history.refresh()
    assertEquals(savedId, history.receipts.value.single().submission.id)
    verify(photos).delete(draftId)
  }

  @Test
  fun renamingASavedReceiptPreservesItsPhotoItemsAndExpense() = runTest {
    val store = MemoryStore()
    val id = java.util.UUID.randomUUID().toString()
    val submission =
        InventorySubmission(id, "owner", "home", listOf(StagedItem("a", "Milk", 1, 100)))
    store.save(submission)
    store.saveDetails(ReceiptDetails(id, "owner", "home", "photo", 100L, 200L))
    val repository = mock(ItemRepository::class.java)
    val history = historyModel(repository, store, mock(ReceiptPhotoStore::class.java))
    owners += ViewModelStore().also { it.put("history", history) }
    runCurrent()
    assertTrue(history.rename(id, "  Courses Migros  "))
    history.refresh()
    val receipt = history.receipts.value.single()
    assertEquals("Courses Migros", receipt.details?.title)
    assertEquals("photo", receipt.details?.photoUri)
    assertEquals(200L, receipt.details?.savedAtMillis)
    assertEquals(submission, receipt.submission)
    verifyNoInteractions(repository)
  }

  @Test
  fun customTitlePersistsAcrossReloadAndMetadataFailurePreventsLeaving() = runTest {
    val store = MemoryStore()
    val id = java.util.UUID.randomUUID().toString()
    val rows = listOf(StagedItem("a", "Milk", 1, 100))
    store.save(InventorySubmission(id, "owner", "home", rows, draft = true))
    store.saveDetails(ReceiptDetails(id, "owner", "home", "photo", 100L))
    val history =
        historyModel(mock(ItemRepository::class.java), store, mock(ReceiptPhotoStore::class.java))
    owners += ViewModelStore().also { it.put("history", history) }
    runCurrent()
    assertTrue(history.editDraft(id, rows, "  Courses Migros  "))
    history.refresh()
    assertEquals("Courses Migros", history.receipts.value.single().details?.title)
    assertEquals("photo", history.receipts.value.single().details?.photoUri)
    store.failDetails = true
    history.saveAndLeave(id, rows, "Weekly groceries")
    runCurrent()
    assertNull(history.savedReview.value)
    assertTrue(history.error.value)
    store.failDetails = false
    history.saveAndLeave(id, rows, "Weekly groceries")
    runCurrent()
    assertEquals(id, history.savedReview.value)
    assertEquals("Weekly groceries", store.details("owner", "home", id)?.title)
  }

  @Test
  fun inventoryConfirmationDoesNotMarkUnconfirmedExpenseSaved() = runTest {
    val store = MemoryStore()
    val id = java.util.UUID.randomUUID().toString()
    val rows = listOf(StagedItem("a", "Apple", 1, 100))
    val expense =
        com.android.sharemate.model.finance.SharedExpense(
            id, "home", "owner", 100, mapOf("owner" to 100L))
    val pending =
        InventorySubmission(
            id,
            "owner",
            "home",
            rows,
            expense = expense,
            inventoryConfirmed = true,
            expenseProgress = com.android.sharemate.model.item.ExpenseProgress.UNKNOWN)
    store.save(pending)
    val repository = mock(ItemRepository::class.java)
    val history = historyModel(repository, store, mock(ReceiptPhotoStore::class.java))
    owners += ViewModelStore().also { it.put("history", history) }
    runCurrent()
    assertEquals(InventorySavePhase.PENDING, history.receipts.value.single().phase)
    verifyNoInteractions(repository)
    store.save(
        pending.withProgress(true, com.android.sharemate.model.item.ExpenseProgress.CONFIRMED))
    history.refresh()
    assertEquals(InventorySavePhase.CONFIRMED, history.receipts.value.single().phase)
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

  @Test
  fun draftsRemainIndependentAndPendingReceiptSurvivesViewModelRecreation() = runTest {
    val store = MemoryStore()
    val photos =
        object : ReceiptPhotoStore {
          override suspend fun retain(sourceUri: String, receiptId: String) = "file:///receipt.jpg"

          override suspend fun delete(receiptId: String) = Unit
        }
    val repository = mock(ItemRepository::class.java)
    val statuses = MutableSharedFlow<InventorySubmissionStatus>(extraBufferCapacity = 2)
    val confirmation = CompletableDeferred<Result<Unit>>()
    `when`(repository.addItems(anyK())).thenReturn(ItemWrite.Queued(confirmation))
    `when`(repository.observeSubmission(anyK())).thenReturn(statuses)
    fun model() =
        ReceiptHistoryViewModel(
                repository,
                store,
                photos,
                "owner",
                "home",
                now = { 100L },
                expenses = mock(ExpenseSubmissionRepository::class.java),
                synchronization = ReceiptHistorySync(store, EmptyReceiptCloud, "owner", "home"))
            .also { owners += ViewModelStore().also { owner -> owner.put("history", it) } }

    val first = model()
    runCurrent()
    val firstId = first.addDraft("photo-a", listOf(StagedItem("a", "Apple", 1, 100)))
    val secondId = first.addDraft("photo-b", listOf(StagedItem("b", "Bread", 2, 300)))
    first.editDraft(firstId, listOf(StagedItem("a", "Green apple", 1, 100)))
    assertEquals(
        "Bread",
        first.receipts.value.first { it.submission.id == secondId }.submission.items.single().name)
    assertEquals(
        "Green apple",
        first.receipts.value.first { it.submission.id == firstId }.submission.items.single().name)

    first.confirm(
        firstId, first.receipts.value.first { it.submission.id == firstId }.submission.items)
    runCurrent()
    assertEquals(
        InventorySavePhase.PENDING,
        first.receipts.value.first { it.submission.id == firstId }.phase)
    owners.first().clear()
    val restored = model()
    runCurrent()
    assertEquals(
        InventorySavePhase.PENDING,
        restored.receipts.value.first { it.submission.id == firstId }.phase)
    assertEquals(
        InventorySavePhase.READY,
        restored.receipts.value.first { it.submission.id == secondId }.phase)
    statuses.emit(InventorySubmissionStatus.CONFIRMED)
    runCurrent()
    assertEquals(
        InventorySavePhase.CONFIRMED,
        restored.receipts.value.first { it.submission.id == firstId }.phase)
    assertEquals(
        100L, restored.receipts.value.first { it.submission.id == firstId }.details?.savedAtMillis)
  }

  @Test
  fun twoPendingReceiptsAreObservedWithoutReplayAndConfirmIndependently() = runTest {
    val store = MemoryStore()
    val photos =
        object : ReceiptPhotoStore {
          override suspend fun retain(sourceUri: String, receiptId: String) = sourceUri

          override suspend fun delete(receiptId: String) = Unit
        }
    val repository = mock(ItemRepository::class.java)
    val statuses = mutableMapOf<String, MutableSharedFlow<InventorySubmissionStatus>>()
    val submittedIds = mutableListOf<String>()
    `when`(repository.addItems(anyK())).thenAnswer { invocation ->
      val submission = invocation.getArgument<InventorySubmission>(0)
      submittedIds += submission.id
      ItemWrite.Queued(CompletableDeferred())
    }
    `when`(repository.observeSubmission(anyK())).thenAnswer { invocation ->
      val id = invocation.getArgument<InventorySubmission>(0).id
      statuses.getOrPut(id) { MutableSharedFlow(extraBufferCapacity = 2) }
    }
    fun model() =
        ReceiptHistoryViewModel(
                repository,
                store,
                photos,
                "owner",
                "home",
                now = { 100L },
                expenses = mock(ExpenseSubmissionRepository::class.java),
                synchronization = ReceiptHistorySync(store, EmptyReceiptCloud, "owner", "home"))
            .also { owners += ViewModelStore().also { owner -> owner.put("history", it) } }

    val first = model()
    runCurrent()
    val firstId = first.addDraft("photo-a", listOf(StagedItem("a", "Apple", 1, 100)))
    val secondId = first.addDraft("photo-b", listOf(StagedItem("b", "Bread", 1, 200)))
    first.confirm(
        firstId, first.receipts.value.first { it.submission.id == firstId }.submission.items)
    first.confirm(
        secondId, first.receipts.value.first { it.submission.id == secondId }.submission.items)
    runCurrent()
    assertEquals(listOf(firstId, secondId), submittedIds)

    owners.first().clear()
    val restored = model()
    runCurrent()
    assertEquals(1, submittedIds.count { it == firstId })
    assertEquals(1, submittedIds.count { it == secondId })
    assertTrue(restored.receipts.value.all { it.phase == InventorySavePhase.PENDING })
    assertEquals(
        setOf("photo-a", "photo-b"),
        restored.receipts.value.mapNotNull { it.details?.photoUri }.toSet())

    statuses.getValue(firstId).emit(InventorySubmissionStatus.CONFIRMED)
    runCurrent()
    assertEquals(
        InventorySavePhase.CONFIRMED,
        restored.receipts.value.first { it.submission.id == firstId }.phase)
    assertEquals(
        InventorySavePhase.PENDING,
        restored.receipts.value.first { it.submission.id == secondId }.phase)
    statuses.getValue(secondId).emit(InventorySubmissionStatus.CONFIRMED)
    runCurrent()
    assertTrue(restored.receipts.value.all { it.phase == InventorySavePhase.CONFIRMED })
  }

  @Test
  fun rejectedSubmissionKeepsReviewedRowsForRetry() = runTest {
    val store = MemoryStore()
    val photos =
        object : ReceiptPhotoStore {
          override suspend fun retain(sourceUri: String, receiptId: String) = sourceUri

          override suspend fun delete(receiptId: String) = Unit
        }
    val repository = mock(ItemRepository::class.java)
    val attempts = mutableListOf<InventorySubmission>()
    val statuses = MutableSharedFlow<InventorySubmissionStatus>(extraBufferCapacity = 1)
    `when`(repository.addItems(anyK())).thenAnswer { invocation ->
      val submission = invocation.getArgument<InventorySubmission>(0)
      attempts += submission
      if (attempts.size == 1) ItemWrite.Rejected(IllegalStateException("offline"))
      else ItemWrite.Queued(CompletableDeferred())
    }
    `when`(repository.observeSubmission(anyK())).thenReturn(statuses)
    val history = historyModel(repository, store, photos)
    owners += ViewModelStore().also { it.put("history", history) }
    runCurrent()
    val id = history.addDraft("photo", listOf(StagedItem("row", "Milk", 2, 450)))
    history.confirm(id, history.receipts.value.single().submission.items)
    runCurrent()
    statuses.emit(InventorySubmissionStatus.MISSING)
    runCurrent()
    assertEquals(InventorySavePhase.FAILED, history.receipts.value.single().phase)
    assertEquals("Milk", history.receipts.value.single().submission.items.single().name)

    history.retry(id)
    runCurrent()
    assertEquals(InventorySavePhase.PENDING, history.receipts.value.single().phase)
    assertEquals(listOf(id, id), attempts.map { it.id })
    assertEquals(attempts[0].items, attempts[1].items)
  }

  @Test
  fun failedMetadataWriteRollsBackDraftAndPhoto() = runTest {
    val store = MemoryStore().apply { failDetails = true }
    val deleted = mutableListOf<String>()
    val photos =
        object : ReceiptPhotoStore {
          override suspend fun retain(sourceUri: String, receiptId: String) = sourceUri

          override suspend fun delete(receiptId: String) {
            deleted += receiptId
          }
        }
    val history = historyModel(mock(ItemRepository::class.java), store, photos)
    owners += ViewModelStore().also { it.put("history", history) }
    runCurrent()

    assertTrue(
        runCatching { history.addDraft("photo", listOf(StagedItem("a", "Apple", 1, 100))) }
            .isFailure)
    assertTrue(store.list("owner", "home").isEmpty())
    assertEquals(1, deleted.size)
    assertTrue(history.receipts.value.isEmpty())
  }

  @Test
  fun serverMissingKeepsReviewedRowsAvailableForRetry() = runTest {
    val store = MemoryStore()
    val photos =
        object : ReceiptPhotoStore {
          override suspend fun retain(sourceUri: String, receiptId: String) = sourceUri

          override suspend fun delete(receiptId: String) = Unit
        }
    val statuses = MutableSharedFlow<InventorySubmissionStatus>(extraBufferCapacity = 1)
    val repository = mock(ItemRepository::class.java)
    `when`(repository.addItems(anyK())).thenReturn(ItemWrite.Queued(CompletableDeferred()))
    `when`(repository.observeSubmission(anyK())).thenReturn(statuses)
    val history = historyModel(repository, store, photos)
    owners += ViewModelStore().also { it.put("history", history) }
    runCurrent()
    val rows = listOf(StagedItem("a", "Apple", 1, 100))
    val id = history.addDraft("photo", rows)
    history.confirm(id, rows)
    runCurrent()

    statuses.emit(InventorySubmissionStatus.MISSING)
    runCurrent()
    assertEquals(InventorySavePhase.FAILED, history.receipts.value.single().phase)
    assertEquals(rows, history.receipts.value.single().submission.items)
    history.retry(id)
    runCurrent()
    assertEquals(InventorySavePhase.PENDING, history.receipts.value.single().phase)
    verify(repository, times(2)).addItems(anyK())
  }

  @Test
  fun invalidReviewAndFailedEditLeaveDurableDraftUntouched() = runTest {
    val store = MemoryStore()
    val photos =
        object : ReceiptPhotoStore {
          override suspend fun retain(sourceUri: String, receiptId: String) = sourceUri

          override suspend fun delete(receiptId: String) = Unit
        }
    val repository = mock(ItemRepository::class.java)
    val history = historyModel(repository, store, photos)
    owners += ViewModelStore().also { it.put("history", history) }
    runCurrent()
    val original = listOf(StagedItem("a", "Apple", 1, 100))
    val id = history.addDraft("photo", original)
    store.failSave = true
    assertFalse(history.editDraft(id, listOf(StagedItem("b", "Bread", 1, 200))))
    assertTrue(history.error.value)
    assertEquals(original, history.receipts.value.single().submission.items)
    assertEquals(original, store.list("owner", "home").single().items)

    history.confirm(id, emptyList())
    assertEquals(InventorySavePhase.FAILED, history.receipts.value.single().phase)
    assertTrue(history.receipts.value.single().submission.draft)
    verifyNoInteractions(repository)
  }

  @Test
  fun refreshFailureKeepsExistingHistoryAndSetsError() = runTest {
    val store = MemoryStore()
    val photos =
        object : ReceiptPhotoStore {
          override suspend fun retain(sourceUri: String, receiptId: String) = sourceUri

          override suspend fun delete(receiptId: String) = Unit
        }
    val history = historyModel(mock(ItemRepository::class.java), store, photos)
    owners += ViewModelStore().also { it.put("history", history) }
    runCurrent()
    history.addDraft("photo", listOf(StagedItem("a", "Apple", 1, 100)))
    store.failList = true
    history.refresh()
    assertTrue(history.error.value)
    assertEquals("Apple", history.receipts.value.single().submission.items.single().name)
    store.failList = false
    history.refresh()
    assertFalse(history.error.value)
  }

  @Test
  fun failedLocalConfirmationKeepsDraftAndAllowsLaterRetry() = runTest {
    val store = MemoryStore()
    val photos =
        object : ReceiptPhotoStore {
          override suspend fun retain(sourceUri: String, receiptId: String) = sourceUri

          override suspend fun delete(receiptId: String) = Unit
        }
    val repository = mock(ItemRepository::class.java)
    `when`(repository.addItems(anyK())).thenReturn(ItemWrite.Queued(CompletableDeferred()))
    `when`(repository.observeSubmission(anyK())).thenReturn(MutableSharedFlow())
    val history = historyModel(repository, store, photos)
    owners += ViewModelStore().also { it.put("history", history) }
    runCurrent()
    val rows = listOf(StagedItem("a", "Apple", 1, 100))
    val id = history.addDraft("photo", rows)
    store.failSave = true
    history.confirm(id, rows)
    runCurrent()
    assertEquals(InventorySavePhase.FAILED, history.receipts.value.single().phase)
    assertTrue(history.receipts.value.single().submission.draft)
    assertTrue(store.list("owner", "home").single().draft)
    verifyNoInteractions(repository)

    store.failSave = false
    history.retry(id)
    runCurrent()
    assertEquals(InventorySavePhase.PENDING, history.receipts.value.single().phase)
    assertFalse(store.list("owner", "home").single().draft)
    verify(repository).addItems(anyK())
  }

  @Test
  fun failedReplayCanRetryAgainWithoutChangingReviewedRows() = runTest {
    val store = MemoryStore()
    val photos =
        object : ReceiptPhotoStore {
          override suspend fun retain(sourceUri: String, receiptId: String) = sourceUri

          override suspend fun delete(receiptId: String) = Unit
        }
    val repository = mock(ItemRepository::class.java)
    val attempts = mutableListOf<InventorySubmission>()
    `when`(repository.addItems(anyK())).thenAnswer { invocation ->
      attempts += invocation.getArgument<InventorySubmission>(0)
      if (attempts.size == 1) ItemWrite.Rejected(IllegalStateException("offline"))
      else if (attempts.size == 2) throw IllegalStateException("transport unavailable")
      else ItemWrite.Queued(CompletableDeferred())
    }
    val statuses = MutableSharedFlow<InventorySubmissionStatus>()
    `when`(repository.observeSubmission(anyK())).thenReturn(statuses)
    val history = historyModel(repository, store, photos)
    owners += ViewModelStore().also { it.put("history", history) }
    runCurrent()
    val rows = listOf(StagedItem("a", "Apple", 1, 100))
    val id = history.addDraft("photo", rows)
    history.confirm(id, rows)
    runCurrent()
    statuses.emit(InventorySubmissionStatus.MISSING)
    runCurrent()
    assertEquals(InventorySavePhase.FAILED, history.receipts.value.single().phase)

    history.retry(id)
    runCurrent()
    assertEquals(InventorySavePhase.PENDING, history.receipts.value.single().phase)
    history.retry(id)
    runCurrent()
    assertEquals(InventorySavePhase.PENDING, history.receipts.value.single().phase)
    assertEquals(3, attempts.size)
    assertTrue(attempts.all { it.id == id && it.items == rows })
  }

  @Test
  fun confirmationFailureAndRetrySuccessPreserveRowsAndIgnoreDuplicateActions() = runTest {
    val store = MemoryStore()
    val photos =
        object : ReceiptPhotoStore {
          override suspend fun retain(sourceUri: String, receiptId: String) = sourceUri

          override suspend fun delete(receiptId: String) = Unit
        }
    val repository = mock(ItemRepository::class.java)
    val first = CompletableDeferred<Result<Unit>>()
    val second = CompletableDeferred<Result<Unit>>()
    `when`(repository.addItems(anyK()))
        .thenReturn(ItemWrite.Queued(first), ItemWrite.Queued(second))
    `when`(repository.observeSubmission(anyK())).thenReturn(MutableSharedFlow())
    val history = historyModel(repository, store, photos)
    owners += ViewModelStore().also { it.put("history", history) }
    runCurrent()
    val rows = listOf(StagedItem("a", "Apple", 1, 100))
    val id = history.addDraft("photo", rows)
    history.confirm("missing", rows)
    history.retry("missing")
    assertFalse(history.editDraft("missing", rows))
    history.confirm(id, rows)
    runCurrent()
    history.confirm(id, rows)
    history.retry(id)
    assertFalse(history.editDraft(id, rows))
    first.complete(Result.failure(IllegalStateException("refused")))
    runCurrent()
    // A queued write can still be confirmed by the server observer after a transport failure.
    assertEquals(InventorySavePhase.PENDING, history.receipts.value.single().phase)
    history.retry(id)
    runCurrent()
    second.complete(Result.success(Unit))
    runCurrent()
    assertEquals(InventorySavePhase.CONFIRMED, history.receipts.value.single().phase)
    assertEquals(rows, history.receipts.value.single().submission.items)
    assertNotNull(store.details("owner", "home", id)?.savedAtMillis)
    history.retry(id)
    runCurrent()
    verify(repository, times(2)).addItems(anyK())
  }

  @Test
  fun legacyReceiptConfirmationRemainsConfirmedAfterStaleMissingStatus() = runTest {
    val store = MemoryStore()
    val rows = listOf(StagedItem("a", "Apple", 1, 100))
    store.save(InventorySubmission(java.util.UUID.randomUUID().toString(), "owner", "home", rows))
    val photos = mock(ReceiptPhotoStore::class.java)
    val repository = mock(ItemRepository::class.java)
    val status = MutableSharedFlow<InventorySubmissionStatus>()
    val confirmation = CompletableDeferred<Result<Unit>>()
    `when`(repository.addItems(anyK())).thenReturn(ItemWrite.Queued(confirmation))
    `when`(repository.observeSubmission(anyK())).thenReturn(status)
    val history = historyModel(repository, store, photos)
    owners += ViewModelStore().also { it.put("history", history) }
    runCurrent()
    status.emit(InventorySubmissionStatus.PENDING)
    assertEquals(InventorySavePhase.PENDING, history.receipts.value.single().phase)
    status.emit(InventorySubmissionStatus.CONFIRMED)
    runCurrent()
    confirmation.complete(Result.success(Unit))
    runCurrent()
    status.emit(InventorySubmissionStatus.MISSING)
    runCurrent()
    assertEquals(InventorySavePhase.CONFIRMED, history.receipts.value.single().phase)
    assertNull(history.receipts.value.single().details)
    assertEquals(rows, history.receipts.value.single().submission.items)
  }

  @Test
  fun cancelledRefreshAndEditPreserveDraftWithoutReportingStorageError() = runTest {
    val store = MemoryStore()
    val repository = mock(ItemRepository::class.java)
    val history = historyModel(repository, store, mock(ReceiptPhotoStore::class.java))
    owners += ViewModelStore().also { it.put("history", history) }
    runCurrent()
    val rows = listOf(StagedItem("a", "Apple", 1, 100))
    val draftId = java.util.UUID.randomUUID().toString()
    store.save(InventorySubmission(draftId, "owner", "home", rows, draft = true))
    history.refresh()
    store.cancelOperations = true
    try {
      history.refresh()
      fail("Refresh must propagate cancellation")
    } catch (_: kotlinx.coroutines.CancellationException) {}
    try {
      history.editDraft(draftId, emptyList())
      fail("Edit must propagate cancellation")
    } catch (_: kotlinx.coroutines.CancellationException) {}
    assertFalse(history.error.value)
    assertEquals(rows, history.receipts.value.single().submission.items)
  }

  @Test
  fun acknowledgedReceiptRefreshNeverReplaysAndRejectedRetryChecksMarker() = runTest {
    val store = MemoryStore()
    val id = java.util.UUID.randomUUID().toString()
    val rows = listOf(StagedItem("a", "Apple", 1, 100))
    store.save(InventorySubmission(id, "owner", "home", rows))
    val repository = mock(ItemRepository::class.java)
    `when`(repository.addItems(anyK()))
        .thenReturn(ItemWrite.Rejected(IllegalStateException("already acknowledged")))
    `when`(repository.observeSubmission(anyK()))
        .thenReturn(kotlinx.coroutines.flow.flowOf(InventorySubmissionStatus.CONFIRMED))
    val history = historyModel(repository, store, mock(ReceiptPhotoStore::class.java))
    owners += ViewModelStore().also { it.put("history", history) }
    runCurrent()
    history.refresh()
    runCurrent()
    assertEquals(InventorySavePhase.CONFIRMED, history.receipts.value.single().phase)
    verify(repository, never()).addItems(anyK())
    val rejectedId = java.util.UUID.randomUUID().toString()
    store.save(InventorySubmission(rejectedId, "owner", "home", rows, draft = true))
    history.refresh()
    history.confirm(rejectedId, rows)
    runCurrent()
    assertEquals(
        InventorySavePhase.CONFIRMED,
        history.receipts.value.first { it.submission.id == rejectedId }.phase)
    verify(repository, times(1)).addItems(anyK())
  }

  @Test
  fun photoFailureIsExposedAndAbandonedSaveDoesNotRequestNavigation() = runTest {
    val store = MemoryStore()
    val id = java.util.UUID.randomUUID().toString()
    val rows = listOf(StagedItem("a", "Apple", 1, 100))
    store.save(InventorySubmission(id, "owner", "home", rows, draft = true))
    val photos = mock(ReceiptPhotoStore::class.java)
    `when`(photos.retain(anyK(), anyK())).thenThrow(IllegalStateException("disk full"))
    val history = historyModel(mock(ItemRepository::class.java), store, photos)
    owners += ViewModelStore().also { it.put("history", history) }
    runCurrent()
    try {
      history.retainScan("photo", rows)
      fail("Expected photo failure")
    } catch (_: IllegalStateException) {}
    assertTrue(history.error.value)
    assertEquals(id, history.receipts.value.single().submission.id)
    history.saveAndLeave(id, rows)
    history.clearSavedReview()
    runCurrent()
    assertNull(history.savedReview.value)
    store.failSave = true
    history.saveAndLeave(id, rows)
    runCurrent()
    assertNull(history.savedReview.value)
    assertTrue(history.error.value)
    store.failSave = false
    history.saveAndLeave(id, rows)
    runCurrent()
    assertEquals(id, history.savedReview.value)
  }

  private class MemoryStore : InventorySubmissionStore {
    override suspend fun deleteReceipt(ownerId: String, householdId: String, id: String) =
        deleteDraft(ownerId, householdId, id)

    private val submissions = mutableMapOf<String, InventorySubmission>()
    private val metadata = mutableMapOf<String, ReceiptDetails>()
    var failDetails = false
    var failSave = false
    var failDelete = false
    var failList = false
    var cancelOperations = false

    override suspend fun load(ownerId: String, householdId: String) =
        list(ownerId, householdId).firstOrNull()

    override suspend fun list(ownerId: String, householdId: String): List<InventorySubmission> {
      if (cancelOperations) throw kotlinx.coroutines.CancellationException()
      if (failList) error("local history unavailable")
      return submissions.values
          .filter { it.ownerId == ownerId && it.householdId == householdId }
          .reversed()
    }

    override suspend fun save(submission: InventorySubmission) {
      if (cancelOperations) throw kotlinx.coroutines.CancellationException()
      if (failSave) error("local write unavailable")
      submissions[submission.id] = submission
    }

    override suspend fun details(ownerId: String, householdId: String, id: String) =
        metadata[id]?.takeIf { it.ownerId == ownerId && it.householdId == householdId }

    override suspend fun saveDetails(details: ReceiptDetails) {
      if (failDetails) error("metadata unavailable")
      metadata[details.id] = details
    }

    override suspend fun deleteDraft(ownerId: String, householdId: String, id: String) {
      if (failDelete) error("Delete failed")
      submissions.remove(id)
      metadata.remove(id)
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun <T> anyK(): T {
    any<T>()
    return null as T
  }
}
