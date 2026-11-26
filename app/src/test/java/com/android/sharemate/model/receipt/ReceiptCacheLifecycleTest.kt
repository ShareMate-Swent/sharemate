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
  fun leavingPreservesTheSameReceiptPhotoUntilItsLastAccountLeaves() = runTest {
    val store = AndroidInventorySubmissionStore(app, scheduleSync = false)
    val row = receipt("bob", "home", false)
    val photo = File(app.filesDir, "receipts/${row.id}.jpg")
    photo.parentFile!!.mkdirs()
    photo.writeText("shared receipt image")
    val metadata = details(row).copy(photoUri = android.net.Uri.fromFile(photo).toString())
    store.save(row)
    store.saveDetails(metadata)
    store.cacheShared("alice", "home", listOf(ReceiptCloudEntry(row, metadata)))

    ReceiptCacheLifecycle(app).evict("alice", "home")

    assertNull(store.forViewer("alice").loadById("bob", "home", row.id))
    assertEquals(row, store.loadById("bob", "home", row.id))
    assertEquals(metadata, store.details("bob", "home", row.id))
    assertEquals("shared receipt image", photo.readText())
    ReceiptCacheLifecycle(app).evict("bob", "home")
    assertFalse(photo.exists())
    assertNull(store.loadById("bob", "home", row.id))
  }

  @Test
  fun leavingPreservesAPhotoTrackedByAnotherViewerBeforeTheirMetadataArrives() = runTest {
    val lifecycle = ReceiptCacheLifecycle(app)
    val id = UUID.randomUUID().toString()
    lifecycle.trackDownload("alice", "home", id)
    lifecycle.trackDownload("bob", "home", id)
    val photo = File(app.filesDir, "receipts/$id.jpg")
    photo.parentFile!!.mkdirs()
    photo.writeText("downloaded image")
    lifecycle.evict("alice", "home")
    assertEquals("downloaded image", photo.readText())
    lifecycle.evict("bob", "home")
    assertFalse(photo.exists())
  }

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

  @Test
  fun failedPhotoRemovalKeepsTheBarrierAndCanBeRetried() = runTest {
    val row = receipt("alice", "home")
    val store = AndroidInventorySubmissionStore(app)
    store.save(row)
    store.saveDetails(details(row))
    val path = File(app.filesDir, "receipts/${row.id}.jpg")
    path.mkdirs()
    val child = File(path, "busy")
    child.writeText("locked payload")
    val lifecycle = ReceiptCacheLifecycle(app)
    assertTrue(runCatching { lifecycle.evict("alice", "home") }.isFailure)
    assertFalse(lifecycle.isCurrent("home"))
    assertNotNull(store.loadById("alice", "home", row.id))
    assertTrue(runCatching { store.save(row) }.isFailure)
    assertTrue(child.delete())
    lifecycle.evict("alice", "home")
    assertFalse(path.exists())
    assertNull(store.loadById("alice", "home", row.id))
  }

  @Test
  fun evictionSkipsMalformedAndForeignScopesAndRejectsUnsafePhotoNames() = runTest {
    val prefs =
        app.getSharedPreferences("inventory-submissions", android.content.Context.MODE_PRIVATE)
    prefs
        .edit()
        .putString("broken:[", "retained")
        .putString("broken:[not-json]", "retained")
        .putString("short:[]", "retained")
        .putString("download:[\"bob\",\"home\"]:photo", "photo")
        .putString("download:[\"alice\",\"home\"]:unsafe", "../outside")
        .commit()
    val lifecycle = ReceiptCacheLifecycle(app)
    assertTrue(runCatching { lifecycle.reconcile("alice", emptySet()) }.isFailure)
    assertFalse(lifecycle.isCurrent("home"))
    prefs.edit().remove("download:[\"alice\",\"home\"]:unsafe").commit()
    lifecycle.evict("alice", "home")
    assertEquals("retained", prefs.getString("broken:[not-json]", null))
    assertEquals("photo", prefs.getString("download:[\"bob\",\"home\"]:photo", null))
    lifecycle.activate("home")
    val current = ReceiptCacheLifecycle(app)
    assertTrue(current.isCurrent("home"))
    assertFalse(lifecycle.isCurrent("home"))
  }

  @Test
  fun departedStoreCannotRecacheSharedEntriesOrAcknowledgeAnExpenseAfterRejoin() = runTest {
    val id = UUID.randomUUID().toString()
    val expense =
        com.android.sharemate.model.finance.SharedExpense(
            id, "home", "alice", 100, mapOf("alice" to 100L))
    val row =
        InventorySubmission(
            id,
            "alice",
            "home",
            listOf(com.android.sharemate.model.staging.StagedItem("milk", "Milk", 1, 100)),
            expense = expense)
    val old = AndroidInventorySubmissionStore(app)
    old.save(row)
    val lifecycle = ReceiptCacheLifecycle(app)
    lifecycle.evict("alice", "home")
    lifecycle.activate("home")
    val fresh = AndroidInventorySubmissionStore(app)
    fresh.save(row)
    assertTrue(runCatching { old.acknowledgeExpense("alice", "home", id, expense) }.isFailure)
    var enqueued = false
    assertTrue(
        runCatching {
              old.recoverInventory("alice", "home", id) {
                enqueued = true
                com.android.sharemate.model.item.ItemWrite.Rejected(
                    IllegalStateException("Unexpected"))
              }
            }
            .isFailure)
    assertFalse(enqueued)
    val peer = receipt("bob", "home", false)
    assertTrue(
        runCatching {
              old.cacheShared("alice", "home", listOf(ReceiptCloudEntry(peer, details(peer))))
            }
            .isFailure)
    assertTrue(fresh.sharedReceipts("alice", "home").isEmpty())
    assertEquals(
        com.android.sharemate.model.item.ExpenseProgress.QUEUED,
        fresh.loadById("alice", "home", id)?.expenseProgress)
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
