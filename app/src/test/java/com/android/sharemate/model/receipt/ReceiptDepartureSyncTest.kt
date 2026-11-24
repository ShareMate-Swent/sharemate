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
    val rows =
        List(2) {
          InventorySubmission(
              UUID.randomUUID().toString(), "alice", "home", emptyList(), draft = true)
        }
    `when`(store.list("alice", "home")).thenReturn(rows)
    val uploaded = mutableListOf<InventorySubmission>()
    ReceiptDepartureSync(store) {
          uploaded.add(it)
          true
        }
        .prepare("alice", "home")
    assertEquals(rows, uploaded)
    assertTrue(
        runCatching { ReceiptDepartureSync(store) { false }.prepare("alice", "home") }.isFailure)
    assertTrue(
        runCatching { ReceiptDepartureSync(store) { error("offline") }.prepare("alice", "home") }
            .isFailure)
    val timeout =
        runCatching {
              ReceiptDepartureSync(store) {
                    kotlinx.coroutines.delay(60_001)
                    true
                  }
                  .prepare("alice", "home")
            }
            .exceptionOrNull()
    assertTrue(timeout is IllegalStateException)
  }
}
