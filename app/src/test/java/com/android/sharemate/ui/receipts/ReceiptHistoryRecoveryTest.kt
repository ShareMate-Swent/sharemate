// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.finance.ExpenseSubmissionRepository
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

  // Stop retries before runTest drains virtual time; @After runs too late for that.
  private fun runHistoryTest(block: suspend TestScope.() -> Unit) = runTest {
    try {
      block()
    } finally {
      lifecycle.clear()
      runCurrent()
    }
  }

  private fun TestScope.store() =
      AndroidInventorySubmissionStore(
          RuntimeEnvironment.getApplication(), StandardTestDispatcher(testScheduler))

  private fun TestScope.model(
      store: InventorySubmissionStore,
      cloud: ReceiptCloudRepository = EmptyReceiptCloud,
      expenses: ExpenseSubmissionRepository = mock(ExpenseSubmissionRepository::class.java)
  ): ReceiptHistoryViewModel =
      ReceiptHistoryViewModel(
              items,
              store,
              photos,
              "owner",
              "home",
              now = { 7 },
              cloud = cloud,
              expenses = expenses)
          .also {
            lifecycle.put(UUID.randomUUID().toString(), it)
            runCurrent()
          }

  @Test
  fun acknowledgedRemoteDeletionSurvivesPhotoAndStoreFailureAndRestart() = runHistoryTest {
    val disk = store()
    disk.save(receipt())
    val expenses = mock(ExpenseSubmissionRepository::class.java)
    val failingStore =
        object : InventorySubmissionStore by disk {
          override suspend fun deleteReceipt(ownerId: String, householdId: String, id: String) {
            throw IOException("Disk full")
          }
        }
    val history = model(failingStore, expenses = expenses)
    `when`(photos.delete(id)).thenThrow(IllegalStateException("Photo busy"))
    assertFalse(history.deleteReceipt(id))
    assertTrue(history.error.value)
    assertTrue(disk.isDeletionAcknowledged("owner", "home", id))
    assertNotNull(disk.loadById("owner", "home", id))
    reset(photos)
    assertFalse(history.deleteReceipt(id))
    assertTrue(history.error.value)
    lifecycle.clear()
    val restarted = model(store(), expenses = expenses)
    assertTrue(restarted.deleteReceipt(id))
    assertFalse(restarted.error.value)
    assertTrue(restarted.receipts.value.isEmpty())
    assertTrue(disk.isDeleted("owner", "home", id))
    verify(expenses, times(1)).deleteReceipt("home", id)
  }

  @Test
  fun refusedDeletionDoesNotReportIoFailureOrTouchTheServer() = runHistoryTest {
    val disk = store()
    val expenses = mock(ExpenseSubmissionRepository::class.java)
    disk.save(receipt(confirmed = false))
    val history = model(disk, expenses = expenses)
    assertFalse(history.deleteReceipt(id))
    assertFalse(history.error.value)
    disk.save(receipt())
    disk.saveCorrection(ReceiptCorrection("pending", receipt(), receipt(), "Edited"))
    history.refresh()
    assertFalse(history.deleteReceipt(id))
    assertFalse(history.error.value)
    assertFalse(history.deleteReceipt("absent"))
    assertFalse(disk.isDeletionAcknowledged("owner", "home", id))
    verifyNoInteractions(expenses, photos)
  }

  @Test
  fun cloudUpdatesConfirmedContentButNeverOverwritesDraftsOrPendingCorrections() = runHistoryTest {
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
    updates.emit(
        listOf(remote.copy(details = remote.details.copy(title = "Changed title", revision = 1))))
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
  fun titleFallbacksAndFailuresKeepTheCurrentReviewRecoverable() = runHistoryTest {
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
  fun cancellationsPropagateWhileResumeAndCloudFailuresBecomeVisible() = runHistoryTest {
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
    assertTrue(history.syncError.value)
    history.refresh()
    assertFalse(history.error.value)
    assertTrue(history.syncError.value)
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
      assertTrue(observing.syncError.value)
      observing.refresh()
      assertFalse(observing.error.value)
      assertTrue(observing.syncError.value)
    }
  }
}
