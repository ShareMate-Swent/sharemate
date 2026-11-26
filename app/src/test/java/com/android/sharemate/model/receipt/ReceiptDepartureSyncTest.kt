// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.receipt

import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.staging.InventorySubmissionStore
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class ReceiptDepartureSyncTest {
  @Test
  fun departureWaitsForEveryOwnReceiptAndRejectsAnUnfinishedUpload() = runTest {
    val store = mock(InventorySubmissionStore::class.java)
    `when`(store.forViewer("alice")).thenReturn(store)
    `when`(store.sharedReceipts("alice", "home")).thenReturn(emptyList())
    val rows =
        List(2) {
          InventorySubmission(
              UUID.randomUUID().toString(), "alice", "home", emptyList(), draft = true)
        }
    `when`(store.list("alice", "home")).thenReturn(rows)
    val uploaded = mutableListOf<InventorySubmission>()
    ReceiptDepartureSync(store) { receipt, actor ->
          assertEquals("alice", actor)
          uploaded.add(receipt)
          true
        }
        .prepare("alice", "home")
    assertEquals(rows, uploaded)
    assertTrue(
        runCatching { ReceiptDepartureSync(store) { _, _ -> false }.prepare("alice", "home") }
            .isFailure)
    assertTrue(
        runCatching {
              ReceiptDepartureSync(store) { _, _ -> error("offline") }.prepare("alice", "home")
            }
            .isFailure)
    val timeout =
        runCatching {
              ReceiptDepartureSync(store) { _, _ ->
                    kotlinx.coroutines.delay(60_001)
                    true
                  }
                  .prepare("alice", "home")
            }
            .exceptionOrNull()
    assertTrue(timeout is IllegalStateException)
  }

  @Test
  fun productionPreparationUploadsDraftsAndTheAcknowledgedPurchase() = runTest {
    val store = mock(InventorySubmissionStore::class.java)
    `when`(store.forViewer("alice")).thenReturn(store)
    `when`(store.sharedReceipts("alice", "home")).thenReturn(emptyList())
    val items = mock(com.android.sharemate.model.item.ItemRepository::class.java)
    val expenses = mock(com.android.sharemate.model.finance.ExpenseSubmissionRepository::class.java)
    val draft =
        InventorySubmission(
            UUID.randomUUID().toString(), "alice", "home", emptyList(), draft = true)
    val id = UUID.randomUUID().toString()
    val expense =
        com.android.sharemate.model.finance.SharedExpense(
            id, "home", "alice", 100, mapOf("alice" to 100L))
    val purchase =
        InventorySubmission(
            id,
            "alice",
            "home",
            listOf(com.android.sharemate.model.staging.StagedItem("milk", "Milk", 1, 100)),
            expense = expense)
    val confirmed =
        purchase.withProgress(true, com.android.sharemate.model.item.ExpenseProgress.CONFIRMED)
    `when`(store.list("alice", "home")).thenReturn(listOf(draft, purchase))
    `when`(store.loadById("alice", "home", draft.id)).thenReturn(draft)
    `when`(store.loadById("alice", "home", id)).thenReturn(purchase, confirmed, confirmed)
    `when`(items.observeSubmission(purchase))
        .thenReturn(
            kotlinx.coroutines.flow.flowOf(
                com.android.sharemate.model.item.InventorySubmissionStatus.CONFIRMED))
    val draftDetails =
        com.android.sharemate.model.staging.ReceiptDetails(draft.id, "alice", "home", "photo", 1)
    val details =
        com.android.sharemate.model.staging.ReceiptDetails(id, "alice", "home", "photo", 2)
    `when`(store.details("alice", "home", draft.id)).thenReturn(draftDetails)
    `when`(store.details("alice", "home", id)).thenReturn(details)
    val uploads = mutableListOf<ReceiptCloudEntry>()
    ReceiptDepartureSync.create(store, items, expenses) { uploads.add(it) }.prepare("alice", "home")
    assertEquals(
        listOf(ReceiptCloudEntry(draft, draftDetails), ReceiptCloudEntry(confirmed, details)),
        uploads)
    verify(expenses).recordExpense(expense)
    verify(store).acknowledgeExpense("alice", "home", id, expense)
    verify(items, never()).addItems(purchase)
  }

  @Test
  fun departureRejectsMissingMetadataAndFailedUploadAndPropagatesCancellation() = runTest {
    val store = mock(InventorySubmissionStore::class.java)
    `when`(store.forViewer("alice")).thenReturn(store)
    `when`(store.sharedReceipts("alice", "home")).thenReturn(emptyList())
    val items = mock(com.android.sharemate.model.item.ItemRepository::class.java)
    val expenses = mock(com.android.sharemate.model.finance.ExpenseSubmissionRepository::class.java)
    val receipt =
        InventorySubmission(
            UUID.randomUUID().toString(), "alice", "home", emptyList(), draft = true)
    `when`(store.list("alice", "home")).thenReturn(listOf(receipt))
    `when`(store.loadById("alice", "home", receipt.id)).thenReturn(receipt)
    var uploads = 0
    val sync =
        ReceiptDepartureSync.create(store, items, expenses) {
          uploads++
          error("Offline")
        }
    assertTrue(runCatching { sync.prepare("alice", "home") }.isFailure)
    assertEquals(0, uploads)
    `when`(store.details("alice", "home", receipt.id))
        .thenReturn(
            com.android.sharemate.model.staging.ReceiptDetails(
                receipt.id, "alice", "home", "photo", 1))
    assertEquals(
        "Offline", runCatching { sync.prepare("alice", "home") }.exceptionOrNull()?.message)
    assertEquals(1, uploads)
    `when`(store.loadById("alice", "home", receipt.id)).thenReturn(null)
    assertTrue(runCatching { sync.prepare("alice", "home") }.isFailure)
    assertEquals(1, uploads)
    val unfinished =
        InventorySubmission(
            receipt.id,
            "alice",
            "home",
            listOf(com.android.sharemate.model.staging.StagedItem("milk", "Milk")))
    `when`(store.loadById("alice", "home", receipt.id)).thenReturn(unfinished)
    `when`(items.observeSubmission(unfinished))
        .thenReturn(
            kotlinx.coroutines.flow.flowOf(
                com.android.sharemate.model.item.InventorySubmissionStatus.CONFIRMED))
    assertTrue(runCatching { sync.prepare("alice", "home") }.isFailure)
    assertEquals(1, uploads)
    val cancelled =
        runCatching {
              ReceiptDepartureSync(store) { _, _ ->
                    throw kotlinx.coroutines.CancellationException("Logout")
                  }
                  .prepare("alice", "home")
            }
            .exceptionOrNull()
    assertTrue(cancelled is kotlinx.coroutines.CancellationException)
    `when`(store.list("alice", "home")).thenReturn(emptyList())
    sync.prepare("alice", "home")
    assertEquals(1, uploads)
  }

  @Test
  fun departureFlushesAnotherCreatorsCorrectionThroughTheDepartingViewer() = runTest {
    val store = mock(InventorySubmissionStore::class.java)
    val viewer = mock(InventorySubmissionStore::class.java)
    val items = mock(com.android.sharemate.model.item.ItemRepository::class.java)
    val expenses = mock(com.android.sharemate.model.finance.ExpenseSubmissionRepository::class.java)
    val id = UUID.randomUUID().toString()
    val expense =
        com.android.sharemate.model.finance.SharedExpense(
            id, "home", "bob", 100, mapOf("alice" to 100L))
    val receipt =
        InventorySubmission(
            id,
            "bob",
            "home",
            listOf(com.android.sharemate.model.staging.StagedItem("milk", "Milk", 1, 100)),
            expense = expense)
    val correction =
        com.android.sharemate.model.staging.ReceiptCorrection(
            UUID.randomUUID().toString(), receipt, receipt, "Corrected title")
    `when`(store.forViewer("alice")).thenReturn(viewer)
    `when`(viewer.forViewer("alice")).thenReturn(viewer)
    `when`(viewer.list("alice", "home")).thenReturn(emptyList())
    `when`(viewer.sharedReceipts("alice", "home")).thenReturn(listOf(receipt))
    `when`(viewer.loadById("bob", "home", id)).thenReturn(receipt)
    `when`(viewer.pendingCorrection("bob", "home", id)).thenReturn(correction)
    val sync =
        ReceiptDepartureSync.create(store, items, expenses) {
          error("Alice must not overwrite Bob's receipt backup")
        }
    val offline = IllegalStateException("Offline")
    val cancelled = kotlinx.coroutines.CancellationException("Leaving cancelled")
    for (failure in listOf(offline, cancelled)) {
      doThrow(failure).`when`(expenses).correctReceipt(correction)
      assertSame(failure, runCatching { sync.prepare("alice", "home") }.exceptionOrNull())
      verify(viewer, never()).acknowledgeCorrection(correction)
      verify(viewer, never()).clearCorrection("bob", "home", id)
    }
    doAnswer { Unit }.`when`(expenses).correctReceipt(correction)
    sync.prepare("alice", "home")
    verify(expenses, times(3)).correctReceipt(correction)
    verify(viewer).acknowledgeCorrection(correction)
    verifyNoInteractions(items)
    verify(store, never()).pendingCorrection("bob", "home", id)
  }
}
