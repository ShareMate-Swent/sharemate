// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.finance.SharedExpense
import com.android.sharemate.model.item.*
import com.android.sharemate.model.receipt.*
import com.android.sharemate.model.staging.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptHistoryRecoveryTest {
  private val lifecycle = ViewModelStore()
  private val items = mock(ItemRepository::class.java)
  private val photos = mock(ReceiptPhotoStore::class.java)
  private val id = UUID.randomUUID().toString()
  private val rows = listOf(StagedItem("row", "Milk", 1, 100))

  private fun receipt(draft: Boolean = false, confirmed: Boolean = true, name: String = "Milk") =
      InventorySubmission(
          id,
          "owner",
          "home",
          listOf(rows[0].copy(name = name)),
          draft,
          if (draft) null else SharedExpense(id, "home", "owner", 100, mapOf("owner" to 100L)),
          !draft && confirmed,
          if (!draft && confirmed) ExpenseProgress.CONFIRMED else ExpenseProgress.QUEUED)

  @Before
  fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
  }

  @After
  fun tearDown() {
    lifecycle.clear()
    Dispatchers.resetMain()
  }

  private fun TestScope.store() =
      AndroidInventorySubmissionStore(
          RuntimeEnvironment.getApplication(), StandardTestDispatcher(testScheduler))

  private fun TestScope.model(
      store: InventorySubmissionStore,
      cloud: ReceiptCloudRepository? = null
  ): ReceiptHistoryViewModel =
      ReceiptHistoryViewModel(items, store, photos, "owner", "home", now = { 7 }, cloud = cloud)
          .also {
            lifecycle.put(UUID.randomUUID().toString(), it)
            runCurrent()
          }

  @Test
  fun viewerHistoryLoadsSharedMetadataWithoutChangingCreatorsJournal() = runTest {
    val disk = store()
    val peer = InventorySubmission(id, "alice", "home", rows)
    val details = ReceiptDetails(id, "alice", "home", "", 1, title = "Shared groceries")
    val cloud =
        object : ReceiptCloudRepository {
          override fun observe(ownerId: String, householdId: String) =
              flowOf(listOf(ReceiptCloudEntry(peer, details)))
        }
    val history = model(disk.forViewer("owner"), cloud)
    assertEquals(peer, history.receipts.value.single().submission)
    assertEquals(details, history.receipts.value.single().details)
    assertTrue(disk.list("alice", "home").isEmpty())
  }

  @Test
  fun cloudUpdatesConfirmedContentButNeverOverwritesDraftsOrPendingCorrections() = runTest {
    val store = store()
    val updates = MutableSharedFlow<List<ReceiptCloudEntry>>()
    val cloud =
        object : ReceiptCloudRepository {
          override fun observe(ownerId: String, householdId: String) = updates
        }
    val history = model(store, cloud)
    val remote =
        ReceiptCloudEntry(
            receipt(name = "Updated"),
            ReceiptDetails(id, "owner", "home", "", 1, title = "New title"))
    for (local in listOf(receipt(draft = true), receipt(confirmed = false), receipt())) {
      store.save(local)
      history.refresh()
      updates.emit(listOf(remote))
      runCurrent()
      assertEquals(
          if (local.expenseProgress == ExpenseProgress.CONFIRMED) remote.submission else local,
          store.load("owner", "home"))
    }
    updates.emit(listOf(remote))
    runCurrent()
    updates.emit(listOf(remote.copy(details = remote.details.copy(title = "Changed title"))))
    runCurrent()
    assertEquals("Changed title", history.receipts.value.single().details?.title)
    store.saveCorrection(ReceiptCorrection("revision", remote.submission, receipt(), "Pending"))
    history.refresh()
    assertEquals(InventorySavePhase.PENDING, history.receipts.value.single().phase)
    updates.emit(listOf(remote))
    runCurrent()
    assertEquals("Changed title", store.details("owner", "home", id)?.title)
    store.clearCorrection("owner", "home", id)
    store.deleteReceipt("owner", "home", id)
    updates.emit(listOf(remote))
    runCurrent()
    assertTrue(history.receipts.value.isEmpty())
    verifyNoInteractions(items)
  }

  @Test
  fun titleFallbacksAndFailuresKeepTheCurrentReviewRecoverable() = runTest {
    val disk = store()
    disk.save(receipt(draft = true))
    var failure: Exception? = null
    val store =
        object : InventorySubmissionStore by disk {
          override suspend fun saveDetails(details: ReceiptDetails) {
            failure?.let { throw it }
            disk.saveDetails(details)
          }
        }
    val history = model(store)
    assertFalse(history.rename("absent", "Title"))
    assertTrue(history.rename(id, " First "))
    assertEquals("First", history.receipts.value.single().details?.title)
    history.updateDraft(id, rows, "Second")
    runCurrent()
    assertEquals("Second", history.receipts.value.single().details?.title)
    failure = IOException("Disk full")
    assertFalse(history.rename(id, "Lost"))
    assertTrue(history.error.value)
    failure = CancellationException("Leaving")
    assertTrue(
        runCatching { history.rename(id, "Cancelled") }.exceptionOrNull() is CancellationException)
    failure = null
    val otherId = UUID.randomUUID().toString()
    disk.save(InventorySubmission(otherId, "owner", "home", rows, draft = true))
    history.refresh()
    assertTrue(history.editDraft(otherId, rows, "Missing metadata"))
    assertFalse(history.editDraft("absent", rows))
    assertFalse(history.deleteReceipt("absent"))
    assertFalse(history.discardDraft("absent"))
    `when`(photos.delete(id)).thenThrow(IllegalStateException("Already gone"))
    assertTrue(history.discardDraft(id))
    assertFalse(history.error.value)
  }

  @Test
  fun cancellationsPropagateWhileResumeAndCloudFailuresBecomeVisible() = runTest {
    val disk = store()
    disk.save(receipt(draft = true))
    val cancelled = CancellationException("Stopped")
    val store =
        object : InventorySubmissionStore by disk {
          override suspend fun resumeSync(ownerId: String, householdId: String) {
            throw IOException("Offline")
          }

          override suspend fun deleteDraft(ownerId: String, householdId: String, id: String) {
            throw cancelled
          }
        }
    val history = model(store)
    assertTrue(history.error.value)
    assertTrue(runCatching { history.discardDraft(id) }.exceptionOrNull() is CancellationException)
    `when`(photos.delete(id)).thenThrow(cancelled)
    assertTrue(runCatching { history.deleteReceipt(id) }.exceptionOrNull() is CancellationException)
    for (deletions in listOf(false, true)) {
      val disconnected = CompletableDeferred<Unit>()
      val cloud =
          object : ReceiptCloudRepository {
            override fun observe(
                ownerId: String,
                householdId: String
            ): Flow<List<ReceiptCloudEntry>> =
                if (deletions) emptyFlow()
                else
                    flow {
                      disconnected.await()
                      throw IOException("Disconnected")
                    }

            override fun observeDeleted(ownerId: String, householdId: String): Flow<Set<String>> =
                if (deletions)
                    flow {
                      disconnected.await()
                      throw IOException("Disconnected")
                    }
                else emptyFlow()
          }
      val observing = model(disk, cloud)
      disconnected.complete(Unit)
      runCurrent()
      assertTrue(observing.error.value)
    }
  }
}
