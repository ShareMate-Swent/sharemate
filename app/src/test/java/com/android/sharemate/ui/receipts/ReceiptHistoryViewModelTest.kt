package com.android.sharemate.ui.receipts

import androidx.lifecycle.ViewModelStore
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
  private val owners = mutableListOf<ViewModelStore>()

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
        ReceiptHistoryViewModel(repository, store, photos, "owner", "home", { 100L }).also {
          owners += ViewModelStore().also { owner -> owner.put("history", it) }
        }

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
  fun twoPendingReceiptsReplaySeparatelyAndConfirmIndependently() = runTest {
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
        ReceiptHistoryViewModel(repository, store, photos, "owner", "home", { 100L }).also {
          owners += ViewModelStore().also { owner -> owner.put("history", it) }
        }

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
    assertEquals(2, submittedIds.count { it == firstId })
    assertEquals(2, submittedIds.count { it == secondId })
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
    val history = ReceiptHistoryViewModel(repository, store, photos, "owner", "home")
    owners += ViewModelStore().also { it.put("history", history) }
    runCurrent()
    val id = history.addDraft("photo", listOf(StagedItem("row", "Milk", 2, 450)))
    history.confirm(id, history.receipts.value.single().submission.items)
    runCurrent()
    assertEquals(InventorySavePhase.FAILED, history.receipts.value.single().phase)
    assertEquals("Milk", history.receipts.value.single().submission.items.single().name)

    history.retry(id)
    runCurrent()
    assertEquals(InventorySavePhase.PENDING, history.receipts.value.single().phase)
    assertEquals(listOf(id, id), attempts.map { it.id })
    assertEquals(attempts[0].items, attempts[1].items)
  }

  private class MemoryStore : InventorySubmissionStore {
    private val submissions = mutableMapOf<String, InventorySubmission>()
    private val metadata = mutableMapOf<String, ReceiptDetails>()

    override suspend fun load(ownerId: String, householdId: String) =
        list(ownerId, householdId).firstOrNull()

    override suspend fun list(ownerId: String, householdId: String) =
        submissions.values
            .filter { it.ownerId == ownerId && it.householdId == householdId }
            .reversed()

    override suspend fun save(submission: InventorySubmission) {
      submissions[submission.id] = submission
    }

    override suspend fun details(ownerId: String, householdId: String, id: String) =
        metadata[id]?.takeIf { it.ownerId == ownerId && it.householdId == householdId }

    override suspend fun saveDetails(details: ReceiptDetails) {
      metadata[details.id] = details
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun <T> anyK(): T {
    any<T>()
    return null as T
  }
}
