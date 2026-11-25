// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.staging

import com.android.sharemate.model.finance.ExpenseSubmissionRepository
import com.android.sharemate.model.finance.SharedExpense
import com.android.sharemate.model.item.*
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class ReceiptSynchronizationTest {
  private val id = UUID.randomUUID().toString()
  private val rows = listOf(StagedItem("milk", "Milk", 1, 100))
  private val expense = SharedExpense(id, "home", "alice", 100, mapOf("alice" to 50L, "bob" to 50L))
  private val items = mock(ItemRepository::class.java)
  private val expenses = mock(ExpenseSubmissionRepository::class.java)
  private val uploaded = mutableListOf<InventorySubmission>()
  private val store =
      object : InventorySubmissionStore {
        var receipt: InventorySubmission? =
            InventorySubmission(id, "alice", "home", rows, expense = expense)
        var correction: ReceiptCorrection? = null
        var metadata: ReceiptDetails? = null

        override suspend fun details(ownerId: String, householdId: String, id: String) = metadata

        override suspend fun saveDetails(details: ReceiptDetails) {
          metadata = details
        }

        override suspend fun load(ownerId: String, householdId: String) = receipt

        override suspend fun save(submission: InventorySubmission) {
          receipt = submission
        }

        override suspend fun pendingCorrection(ownerId: String, householdId: String, id: String) =
            correction

        override suspend fun acknowledgeExpense(
            ownerId: String,
            householdId: String,
            id: String,
            expense: SharedExpense
        ) {
          val current = receipt ?: return
          if (current.expense == expense && correction == null)
              receipt = current.withProgress(true, ExpenseProgress.CONFIRMED)
        }

        override suspend fun recoverInventory(
            ownerId: String,
            householdId: String,
            id: String,
            enqueue: (InventorySubmission) -> ItemWrite
        ): ItemWrite? =
            receipt
                ?.takeIf { !it.draft && !it.inventoryConfirmed && correction == null }
                ?.let(enqueue)

        override suspend fun acknowledgeCorrection(correction: ReceiptCorrection) {
          if (this.correction?.operationId != correction.operationId) return
          receipt = correction.replacement.withProgress(true, ExpenseProgress.CONFIRMED)
          metadata = metadata?.copy(title = correction.title)
          this.correction = null
        }

        override suspend fun clearCorrection(ownerId: String, householdId: String, id: String) {
          correction = null
        }
      }
  private val sync = ReceiptSynchronization(store, items, expenses) { uploaded += it }

  @Test
  fun correctionUpdatesTitleButCannotClearANewerConcurrentEdit() = runTest {
    val original = store.receipt!!
    val correction = ReceiptCorrection("first", original, original, "Changed")
    store.metadata = ReceiptDetails(id, "alice", "home", "photo", 1)
    store.correction = correction
    assertTrue(sync.synchronize("alice", "home", id, "alice", false))
    assertEquals("Changed", store.metadata?.title)
    assertEquals(listOf(store.receipt), uploaded)
    for (newer in listOf<ReceiptCorrection?>(null, correction.copy(operationId = "newer"))) {
      store.correction = correction
      doAnswer {
            store.correction = newer
            Unit
          }
          .`when`(expenses)
          .correctReceipt(correction)
      assertTrue(sync.synchronize("alice", "home", id, "alice", false))
      assertEquals(newer, store.correction)
    }
  }

  @Test
  fun missingAndForeignReceiptsStopAndConcurrentExpenseEditsArePreserved() = runTest {
    val original = store.receipt!!
    store.receipt = null
    assertTrue(sync.synchronize("alice", "home", id, "alice", false))
    store.receipt = original
    assertTrue(sync.synchronize("alice", "home", id, "bob", false))
    verifyNoInteractions(items, expenses)
    val otherExpense = SharedExpense(id, "home", "bob", 100, mapOf("alice" to 100L))
    val edited = InventorySubmission(id, "alice", "home", rows, expense = otherExpense)
    for (latest in listOf(null, edited, original)) {
      store.receipt = original
      store.correction = null
      `when`(items.observeSubmission(original))
          .thenReturn(
              flowOf(InventorySubmissionStatus.PENDING, InventorySubmissionStatus.CONFIRMED))
      doAnswer {
            store.receipt = latest
            if (latest === original)
                store.correction = ReceiptCorrection("pending", original, edited, "Edit")
            expense
          }
          .`when`(expenses)
          .recordExpense(expense)
      assertTrue(sync.synchronize("alice", "home", id, "alice", false))
      assertEquals(latest, store.receipt)
    }
    store.correction = null
    store.receipt = InventorySubmission(id, "alice", "home", rows)
    `when`(items.observeSubmission(store.receipt!!))
        .thenReturn(flowOf(InventorySubmissionStatus.CONFIRMED))
    assertTrue(sync.synchronize("alice", "home", id, "alice", false))
  }

  @Test
  fun queuedExpenseResumesOnceWithoutReinsertingFridgeItems() = runTest {
    `when`(items.observeSubmission(store.receipt!!))
        .thenReturn(flowOf(InventorySubmissionStatus.CONFIRMED))
    assertTrue(sync.synchronize("alice", "home", id, "alice", false))
    verify(expenses).recordExpense(expense)
    assertEquals(ExpenseProgress.CONFIRMED, store.receipt!!.expenseProgress)
    assertEquals(listOf(store.receipt), uploaded)
    `when`(items.observeSubmission(store.receipt!!))
        .thenReturn(flowOf(InventorySubmissionStatus.CONFIRMED))
    assertTrue(sync.synchronize("alice", "home", id, "alice", false))
    verify(expenses, times(1)).recordExpense(expense)
    verifyNoMoreInteractions(expenses)
  }

  @Test
  fun missingInventoryRestartsTheOriginalBatchBeforeRecordingExpense() = runTest {
    val original = store.receipt!!
    `when`(items.observeSubmission(original)).thenReturn(flowOf(InventorySubmissionStatus.MISSING))
    `when`(items.addItems(original))
        .thenReturn(ItemWrite.Queued(kotlinx.coroutines.CompletableDeferred(Result.success(Unit))))
    assertTrue(sync.synchronize("alice", "home", id, "alice", false))
    val order = inOrder(items, expenses)
    order.verify(items).observeSubmission(original)
    order.verify(items).addItems(original)
    order.verify(expenses).recordExpense(expense)
    assertEquals(ExpenseProgress.CONFIRMED, store.receipt!!.expenseProgress)
  }

  @Test
  fun missingFinishedReceiptStopsRetryingWithoutCreatingFinancialData() = runTest {
    store.receipt = store.receipt!!.withProgress(true, ExpenseProgress.CONFIRMED)
    `when`(items.observeSubmission(store.receipt!!))
        .thenReturn(flowOf(InventorySubmissionStatus.MISSING))
    assertTrue(sync.synchronize("alice", "home", id, "alice", false))
    verifyNoInteractions(expenses)
    assertTrue(uploaded.isEmpty())
  }

  @Test
  fun draftBackupNeverCreatesInventoryOrExpenseAndPeerCannotUploadIt() = runTest {
    store.receipt = InventorySubmission(id, "alice", "home", rows, draft = true)
    assertTrue(sync.synchronize("alice", "home", id, "bob", false))
    assertTrue(uploaded.isEmpty())
    assertTrue(sync.synchronize("alice", "home", id, "alice", false))
    assertEquals(listOf(store.receipt), uploaded)
    verifyNoInteractions(items, expenses)
  }

  @Test
  fun peerCorrectionRetainsCreatorAndClearsJournalOnlyAfterAcknowledgement() = runTest {
    val replacement = InventorySubmission(id, "alice", "home", rows, expense = expense)
    val correction =
        ReceiptCorrection(UUID.randomUUID().toString(), store.receipt!!, replacement, "Courses")
    store.correction = correction
    assertTrue(sync.synchronize("alice", "home", id, "bob", false))
    verify(expenses).correctReceipt(correction)
    assertEquals("alice", store.receipt!!.ownerId)
    assertEquals(ExpenseProgress.CONFIRMED, store.receipt!!.expenseProgress)
    assertNull(store.correction)
    assertTrue(uploaded.isEmpty())
    verifyNoInteractions(items)
  }

  @Test
  fun correctionNetworkFailureKeepsDurableJournalForRetry() = runTest {
    val correction =
        ReceiptCorrection(UUID.randomUUID().toString(), store.receipt!!, store.receipt!!, "Courses")
    store.correction = correction
    `when`(expenses.correctReceipt(correction)).thenThrow(IllegalStateException("Offline"))
    try {
      sync.synchronize("alice", "home", id, "bob", false)
      fail("Expected transport failure")
    } catch (_: IllegalStateException) {}
    assertEquals(correction, store.correction)
    assertEquals(correction.original, store.receipt)
  }

  @Test
  fun deletedLocalReceiptStillDeletesCloudBackupAfterRestart() = runTest {
    store.receipt = null
    assertTrue(sync.synchronize("alice", "home", id, "alice", true))
    verify(expenses).deleteReceipt("home", id)
    verifyNoInteractions(items)
    assertTrue(uploaded.isEmpty())
  }
}
