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
            launch(dispatcher) {
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
}
