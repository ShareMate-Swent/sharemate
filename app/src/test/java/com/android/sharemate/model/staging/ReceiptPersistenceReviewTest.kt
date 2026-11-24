// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.staging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.receipt.ReceiptCloudEntry
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReceiptPersistenceReviewTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  private fun entry(name: String = "Milk"): ReceiptCloudEntry {
    val receipt =
        InventorySubmission(
            UUID.randomUUID().toString(), "alice", "home", listOf(StagedItem("row", name, 1, 100)))
    return ReceiptCloudEntry(
        receipt, ReceiptDetails(receipt.id, "alice", "home", "", 1, title = name))
  }

  @Test
  fun deletionBeforeSnapshotSurvivesRestartAndDoesNotBlockValidEntries() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val store = AndroidInventorySubmissionStore(context, dispatcher)
    val deleted = entry()
    val valid = entry("Bread")
    store.applyCloudDeletions("alice", "home", setOf(deleted.submission.id))
    val restarted = AndroidInventorySubmissionStore(context, dispatcher)
    assertTrue(restarted.isDeleted("alice", "home", deleted.submission.id))
    restarted.cacheShared("bob", "home", listOf(deleted, valid))
    assertEquals(listOf(valid.submission), restarted.sharedReceipts("bob", "home"))
    assertTrue(runCatching { restarted.save(deleted.submission) }.isFailure)
  }

  @Test
  fun olderViewerSnapshotCannotOverwriteOwnersRowsOrTitle() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val store = AndroidInventorySubmissionStore(context, dispatcher)
    val older = entry()
    val newer =
        InventorySubmission(
            older.submission.id, "alice", "home", listOf(StagedItem("row", "Oat milk", 2, 100)))
    store.save(newer)
    store.saveDetails(older.details.copy(title = "Updated"))
    store.cacheShared("bob", "home", listOf(older))
    val restarted = AndroidInventorySubmissionStore(context, dispatcher)
    assertEquals(newer, restarted.loadById("alice", "home", newer.id))
    assertEquals("Updated", restarted.details("alice", "home", newer.id)?.title)
    assertEquals(listOf(older.submission), restarted.sharedReceipts("bob", "home"))
    val viewer = AndroidInventorySubmissionStore(context, dispatcher, viewerId = "bob")
    assertEquals("Milk", viewer.details("alice", "home", newer.id)?.title)
  }

  @Test
  fun editArrivingInsideAcknowledgementWaitsAndKeepsItsJournal() = runTest {
    val dispatcher = UnconfinedTestDispatcher(testScheduler)
    val prefs = spy(context.getSharedPreferences("inventory-submissions", Context.MODE_PRIVATE))
    val wrapped = mock(Context::class.java)
    `when`(wrapped.applicationContext).thenReturn(wrapped)
    `when`(wrapped.getSharedPreferences("inventory-submissions", Context.MODE_PRIVATE))
        .thenReturn(prefs)
    val store = AndroidInventorySubmissionStore(wrapped, dispatcher)
    val peer = AndroidInventorySubmissionStore(wrapped, dispatcher)
    val entry = entry()
    store.save(entry.submission)
    store.saveDetails(entry.details)
    val replacement =
        InventorySubmission(
            entry.submission.id,
            "alice",
            "home",
            entry.submission.items,
            expense =
                com.android.sharemate.model.finance.SharedExpense(
                    entry.submission.id, "home", "alice", 100, mapOf("alice" to 100L)))
    val first = ReceiptCorrection("first", entry.submission, replacement, "First")
    val next = first.copy(operationId = "next", title = "Next")
    store.saveCorrection(first)
    val journalKey = "correction:[\"alice\",\"home\"]:${entry.submission.id}"
    var editStarted = false
    var editFinished = false
    doAnswer { invocation ->
          val value = invocation.callRealMethod()
          if (!editStarted) {
            editStarted = true
            launch(dispatcher, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
              peer.saveCorrection(next)
              editFinished = true
            }
            assertFalse("Journal writes must wait for the acknowledgement commit", editFinished)
          }
          value
        }
        .`when`(prefs)
        .getString(journalKey, null)
    store.acknowledgeCorrection(first)
    advanceUntilIdle()
    assertTrue(editFinished)
    assertEquals(next, peer.pendingCorrection("alice", "home", entry.submission.id))
    store.acknowledgeCorrection(first)
    assertEquals(next, peer.pendingCorrection("alice", "home", entry.submission.id))
    assertEquals("First", store.details("alice", "home", entry.submission.id)?.title)
  }

  @Test
  fun serverDeletionPurgesEveryViewerPayloadAndCorrectionAfterRestart() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val store = AndroidInventorySubmissionStore(context, dispatcher)
    val row = entry()
    val replacement = priced(row.submission)
    for (viewer in listOf("bob", "carol")) {
      store.cacheShared(viewer, "home", listOf(row))
      AndroidInventorySubmissionStore(context, dispatcher, viewerId = viewer)
          .saveCorrection(
              ReceiptCorrection("pending-$viewer", row.submission, replacement, "Pending"))
    }
    store.applyCloudDeletions("alice", "home", setOf(row.submission.id))
    for (viewer in listOf("bob", "carol")) {
      val restarted = AndroidInventorySubmissionStore(context, dispatcher, viewerId = viewer)
      assertNull(restarted.loadById("alice", "home", row.submission.id))
      assertNull(restarted.details("alice", "home", row.submission.id))
      assertNull(restarted.pendingCorrection("alice", "home", row.submission.id))
      assertTrue(restarted.sharedReceipts(viewer, "home").isEmpty())
      assertTrue(
          runCatching {
                restarted.saveCorrection(
                    ReceiptCorrection("late", row.submission, replacement, "Late"))
              }
              .isFailure)
    }
  }

  @Test
  fun deletionAfterFilteringDoesNotPreventTheRemainingSnapshotFromBeingCached() = runTest {
    val disk = StandardTestDispatcher(testScheduler, "disk")
    val store = AndroidInventorySubmissionStore(context, disk)
    val gone = entry()
    val valid = entry("Bread")
    val caching =
        launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
          store.cacheShared("bob", "home", listOf(gone, valid))
        }
    // cacheShared has filtered the snapshot, but its first disk write has not run yet.
    AndroidInventorySubmissionStore(context, UnconfinedTestDispatcher(testScheduler))
        .applyCloudDeletions("alice", "home", setOf(gone.submission.id))
    caching.join()
    assertEquals(listOf(valid.submission), store.sharedReceipts("bob", "home"))
  }

  @Test
  fun staleSnapshotCannotReplaceTheViewersAcknowledgedRevisionAfterRestart() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val original = entry()
    val store = AndroidInventorySubmissionStore(context, dispatcher, viewerId = "bob")
    store.cacheShared("bob", "home", listOf(original))
    val replacement = priced(original.submission, "Oat milk")
    val correction = ReceiptCorrection("edit", original.submission, replacement, "Updated")
    store.saveCorrection(correction)
    store.acknowledgeCorrection(correction)
    val restarted = AndroidInventorySubmissionStore(context, dispatcher, viewerId = "bob")
    restarted.cacheShared("bob", "home", listOf(original))
    assertEquals(replacement.items, restarted.loadById("alice", "home", replacement.id)?.items)
    assertEquals("Updated", restarted.details("alice", "home", replacement.id)?.title)
    assertEquals(1L, restarted.details("alice", "home", replacement.id)?.revision)
    val newest =
        ReceiptCloudEntry(
            priced(original.submission, "Newest"),
            original.details.copy(title = "Newest", revision = 2))
    restarted.cacheShared("bob", "home", listOf(newest))
    restarted.cacheShared("bob", "home", listOf(original))
    assertEquals(newest.submission, restarted.loadById("alice", "home", replacement.id))
    assertEquals(2L, restarted.details("alice", "home", replacement.id)?.revision)
  }

  @Test
  fun expenseAcknowledgementKeepsARenamedReceiptWithTheSameExpense() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val store = AndroidInventorySubmissionStore(context, dispatcher)
    val original = priced(entry().submission)
    store.save(original)
    store.saveDetails(ReceiptDetails(original.id, "alice", "home", "", 1))
    val replacement = priced(original, "Renamed")
    val correction = ReceiptCorrection("correction", original, replacement, "New title")
    store.saveCorrection(correction)
    store.acknowledgeCorrection(correction)
    // The expense response still belongs to the original, pre-correction rows.
    store.acknowledgeExpense("alice", "home", original.id, original.expense!!)
    assertEquals(replacement.items, store.loadById("alice", "home", original.id)?.items)
    assertEquals("New title", store.details("alice", "home", original.id)?.title)
  }

  @Test
  fun restartedPurchaseEnqueuesItsOriginalIdentityAndDeletedPurchasesNeverEnqueue() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val store = AndroidInventorySubmissionStore(context, dispatcher)
    val original = priced(entry().submission)
    store.save(original)
    val restarted = AndroidInventorySubmissionStore(context, dispatcher)
    val enqueued = mutableListOf<InventorySubmission>()
    val enqueue: (InventorySubmission) -> com.android.sharemate.model.item.ItemWrite = {
      enqueued += it
      com.android.sharemate.model.item.ItemWrite.Queued(
          kotlinx.coroutines.CompletableDeferred(Result.success(Unit)))
    }
    restarted.recoverInventory("alice", "home", original.id, enqueue)
    assertEquals(listOf(original), enqueued)
    restarted.applyCloudDeletions("alice", "home", setOf(original.id))
    assertNull(restarted.recoverInventory("alice", "home", original.id, enqueue))
    assertEquals(listOf(original), enqueued)
  }

  private fun priced(original: InventorySubmission, name: String = "Milk") =
      InventorySubmission(
          original.id,
          "alice",
          "home",
          listOf(StagedItem("row", name, 1, 100)),
          expense =
              com.android.sharemate.model.finance.SharedExpense(
                  original.id, "home", "alice", 100, mapOf("alice" to 100L)))
}
