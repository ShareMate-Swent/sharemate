// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.receipt

import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.staging.AndroidInventorySubmissionStore
import com.android.sharemate.model.staging.ReceiptDetails
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReceiptCacheLifecycleTest {
  private val app = RuntimeEnvironment.getApplication()

  @Test
  fun leavePurgesOwnAndSharedPhotosButPreservesOtherHouseholdsAndPrivateDrafts() = runTest {
    val store = AndroidInventorySubmissionStore(app)
    val own = receipt("alice", "home")
    val shared = receipt("bob", "home", false)
    val otherHome = receipt("alice", "other")
    val otherAccountDraft = receipt("bob", "home")
    val otherAccountSaved = receipt("bob", "home", false)
    for (row in listOf(own, otherHome, otherAccountDraft, otherAccountSaved)) {
      store.save(row)
      store.saveDetails(details(row))
    }
    store.cacheShared("alice", "home", listOf(ReceiptCloudEntry(shared, details(shared))))
    val photo = File(app.filesDir, "receipts/${shared.id}.jpg")
    photo.parentFile!!.mkdirs()
    photo.writeText("photo")

    store.cacheShared("alice", "home", emptyList())
    ReceiptCacheLifecycle(app).evict("alice", "home")

    assertTrue(store.list("alice", "home").isEmpty())
    assertTrue(store.sharedReceipts("alice", "home").isEmpty())
    assertFalse(photo.exists())
    assertFalse(store.isDeleted("alice", "home", own.id))
    assertEquals(listOf(otherHome), store.list("alice", "other"))
    assertEquals(listOf(otherAccountSaved, otherAccountDraft), store.list("bob", "home"))
    assertNull(store.forViewer("alice").loadById("bob", "home", shared.id))
  }

  @Test
  fun staleWritesStayRejectedAfterRejoinButNewCloudCacheCanRestoreHistory() = runTest {
    val old = AndroidInventorySubmissionStore(app)
    val row = receipt("alice", "home")
    old.save(row)
    val lifecycle = ReceiptCacheLifecycle(app)
    lifecycle.evict("alice", "home")
    assertTrue(runCatching { old.save(row) }.isFailure)
    lifecycle.activate("home")
    assertTrue(runCatching { old.save(row) }.isFailure)
    val restored = AndroidInventorySubmissionStore(app)
    restored.save(row)
    restored.saveDetails(details(row))
    assertEquals(listOf(row), restored.list("alice", "home"))
  }

  @Test
  fun serverReconciliationPurgesDepartedHouseholdAndTrackedDownloadsOnly() = runTest {
    val store = AndroidInventorySubmissionStore(app)
    val gone = receipt("alice", "gone")
    val current = receipt("alice", "current")
    store.save(gone)
    store.save(current)
    val lifecycle = ReceiptCacheLifecycle(app)
    lifecycle.trackDownload("alice", "gone", gone.id)
    val photo = File(app.filesDir, "receipts/${gone.id}.jpg")
    photo.parentFile!!.mkdirs()
    photo.writeText("download without metadata")
    lifecycle.reconcile("alice", setOf("current"))
    assertFalse(photo.exists())
    assertTrue(store.list("alice", "gone").isEmpty())
    assertEquals(listOf(current), store.list("alice", "current"))
    assertTrue(runCatching { lifecycle.write("gone") { photo.writeText("late") } }.isFailure)
    assertFalse(photo.exists())
  }

  private fun receipt(owner: String, home: String, draft: Boolean = true) =
      InventorySubmission(
          UUID.randomUUID().toString(),
          owner,
          home,
          listOf(com.android.sharemate.model.staging.StagedItem("row", "Milk")),
          draft = draft)

  private fun details(row: InventorySubmission) =
      ReceiptDetails(row.id, row.ownerId, row.householdId, "", 1)
}
