// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.staging

import com.android.sharemate.model.item.InventorySubmission
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReceiptSubmissionStoreTest {
  @Test
  fun householdEditorLoadsCreatorReceiptWithoutTransferringOwnership() = runTest {
    val receipt =
        InventorySubmission(
            UUID.randomUUID().toString(),
            "alice",
            "home",
            listOf(StagedItem("milk", "Milk", 1, 100)))
    val details = ReceiptDetails(receipt.id, "alice", "home", "photo", 1)
    val store =
        object : InventorySubmissionStore {
          override suspend fun load(ownerId: String, householdId: String) =
              receipt.takeIf { ownerId == "alice" }

          override suspend fun save(submission: InventorySubmission) {
            assertEquals("alice", submission.ownerId)
          }

          override suspend fun details(ownerId: String, householdId: String, id: String) =
              details.takeIf { ownerId == "alice" }
        }
    val selected = ReceiptSubmissionStore(store, receipt.id, "alice")
    assertEquals(receipt, selected.load("bob", "home"))
    assertEquals(details, selected.details("bob", "home", receipt.id))
    selected.save(receipt)
  }

  @Test
  fun selectedReceiptIsRestoredAndOtherReceiptsCannotOverwriteIt() = runTest {
    val first =
        InventorySubmission(UUID.randomUUID().toString(), "user", "home", emptyList(), draft = true)
    val second =
        InventorySubmission(UUID.randomUUID().toString(), "user", "home", emptyList(), draft = true)
    val entries = mutableListOf(first, second)
    val store =
        object : InventorySubmissionStore {
          override suspend fun load(ownerId: String, householdId: String) = second

          override suspend fun list(ownerId: String, householdId: String) = entries.toList()

          override suspend fun save(submission: InventorySubmission) {
            entries[0] = submission
          }
        }
    val scoped = ReceiptSubmissionStore(store, first.id)
    assertEquals(first, scoped.load("user", "home"))
    try {
      scoped.save(second)
      fail("Expected identity guard")
    } catch (_: IllegalArgumentException) {}
    assertEquals(first, entries[0])
  }
}
