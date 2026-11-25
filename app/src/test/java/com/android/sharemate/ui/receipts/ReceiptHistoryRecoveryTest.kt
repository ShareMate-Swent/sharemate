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
      expenses: ExpenseSubmissionRepository = mock(ExpenseSubmissionRepository::class.java)
  ): ReceiptHistoryViewModel =
      ReceiptHistoryViewModel(
              items, store, photos, "owner", "home", now = { 7 }, expenses = expenses)
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
  fun cancellationsPropagateDuringDeletion() = runHistoryTest {
    val disk = store()
    disk.save(receipt(draft = true))
    val cancelled = CancellationException("Stopped")
    val store =
        object : InventorySubmissionStore by disk {
          override suspend fun deleteDraft(ownerId: String, householdId: String, id: String) {
            throw cancelled
          }
        }
    val history = model(store)
    history.refresh()
    assertFalse(history.error.value)
    assertTrue(runCatching { history.discardDraft(id) }.exceptionOrNull() is CancellationException)
    `when`(photos.delete(id)).thenThrow(cancelled)
    assertTrue(runCatching { history.deleteReceipt(id) }.exceptionOrNull() is CancellationException)
  }
}
