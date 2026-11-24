// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.staging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import com.android.sharemate.model.finance.SharedExpense
import com.android.sharemate.model.item.*
import com.android.sharemate.model.receipt.*
import com.google.firebase.auth.FirebaseAuth
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReceiptJournalRecoveryTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val id = UUID.randomUUID().toString()

  private fun receipt(owner: String = "owner", home: String = "home", draft: Boolean = false) =
      InventorySubmission(
          id,
          owner,
          home,
          listOf(StagedItem("row", "Milk", 1, 100)),
          draft,
          if (draft) null else SharedExpense(id, home, owner, 100, mapOf(owner to 100L)))

  private fun entry(receipt: InventorySubmission) =
      ReceiptCloudEntry(
          receipt, ReceiptDetails(receipt.id, receipt.ownerId, receipt.householdId, "", 1))

  @Test
  fun sharedCacheFiltersPrivateForeignAndOwnReceiptsAndServerDeletionRemovesPhoto() = runTest {
    val store = AndroidInventorySubmissionStore(context, StandardTestDispatcher(testScheduler))
    val own = receipt()
    val peer = receipt("peer")
    store.cacheShared(
        "owner",
        "home",
        listOf(
            entry(own),
            entry(peer),
            entry(receipt("private", draft = true)),
            entry(receipt("foreign", "elsewhere"))))
    assertEquals(listOf(peer), store.sharedReceipts("owner", "home"))
    val photo = File(context.filesDir, "receipts/$id.jpg")
    photo.parentFile!!.mkdirs()
    photo.writeText("photo")
    store.applyCloudDeletions("peer", "home", setOf(id))
    assertTrue(store.isDeleted("peer", "home", id))
    assertTrue(store.sharedReceipts("owner", "home").isEmpty())
    assertFalse(photo.exists())
    AndroidReceiptPhotoStore(context, StandardTestDispatcher(testScheduler)).delete(id)
    store.deleteReceipt("peer", "home", id)
    store.save(own)
    assertTrue(runCatching { store.deleteDraft("owner", "home", id) }.isFailure)
  }

  @Test
  fun restartSchedulesOwnReceiptsPeerCorrectionsAndDeletionMarkers() = runTest {
    val auth = mock(FirebaseAuth::class.java)
    val manager = mock(WorkManager::class.java)
    mockStatic(FirebaseAuth::class.java).use { a ->
      mockStatic(WorkManager::class.java).use { w ->
        a.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
        w.`when`<WorkManager> { WorkManager.getInstance(context) }.thenReturn(manager)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store =
            AndroidInventorySubmissionStore(
                context, dispatcher, scheduleSync = true, viewerId = "owner")
        val own = receipt()
        val peer = receipt("peer")
        store.save(own)
        store.saveDetails(
            entry(own).details.copy(draftPaidBy = "owner", draftParticipants = setOf("owner")))
        store.cacheShared("owner", "home", listOf(entry(peer)))
        store.resumeSync("owner", "home")
        store.saveCorrection(ReceiptCorrection("revision", peer, peer, "Updated"))
        clearInvocations(manager)
        store.resumeSync("owner", "home")
        verify(manager)
            .enqueueUniqueWork(
                eq("receipt-sync-owner-$id"),
                eq(ExistingWorkPolicy.KEEP),
                any<OneTimeWorkRequest>())
        verify(manager)
            .enqueueUniqueWork(
                eq("receipt-sync-peer-$id"), eq(ExistingWorkPolicy.KEEP), any<OneTimeWorkRequest>())
        store.deleteReceipt("owner", "home", id)
        clearInvocations(manager)
        store.resumeSync("owner", "home")
        val request = org.mockito.ArgumentCaptor.forClass(OneTimeWorkRequest::class.java)
        verify(manager)
            .enqueueUniqueWork(
                eq("receipt-sync-owner-$id"), eq(ExistingWorkPolicy.KEEP), request.capture())
        assertTrue(request.value.workSpec.input.getBoolean("delete", false))
        clearInvocations(manager)
        AndroidInventorySubmissionStore(context, dispatcher).resumeSync("owner", "home")
        AndroidInventorySubmissionStore(
                context, dispatcher, legacyActive = true, scheduleSync = true)
            .resumeSync("owner", "home")
        verifyNoInteractions(manager)
      }
    }
  }

  @Test
  fun selectedStoreKeepsCorrectionAndDetailsUnderTheCreatorIdentity() = runTest {
    val store = AndroidInventorySubmissionStore(context, StandardTestDispatcher(testScheduler))
    val receipt = receipt()
    val correction =
        ReceiptCorrection("revision", receipt, receipt, "Title", originalTitle = "Previous title")
    store.save(receipt)
    store.saveDetails(entry(receipt).details)
    store.saveCorrection(correction)
    val restarted = AndroidInventorySubmissionStore(context, StandardTestDispatcher(testScheduler))
    assertEquals(correction, restarted.pendingCorrection("owner", "home", id))
    for (ownerOverride in listOf(null, "owner")) {
      val selected = ReceiptSubmissionStore(store, id, ownerOverride)
      val viewer = if (ownerOverride == null) "owner" else "peer"
      assertEquals(entry(receipt).details, selected.details(viewer, "home", id))
      assertEquals(correction, selected.pendingCorrection(viewer, "home", id))
      selected.clearCorrection(viewer, "home", id)
      assertNull(selected.pendingCorrection(viewer, "home", id))
      store.saveCorrection(correction)
    }
    val unsupported =
        object : InventorySubmissionStore {
          override suspend fun load(ownerId: String, householdId: String) = null

          override suspend fun save(submission: InventorySubmission) = Unit
        }
    assertTrue(runCatching { unsupported.deleteReceipt("owner", "home", id) }.isFailure)
    assertTrue(runCatching { unsupported.saveCorrection(correction) }.isFailure)
    val cloud =
        object : ReceiptCloudRepository {
          override fun observe(ownerId: String, householdId: String) =
              flowOf(emptyList<ReceiptCloudEntry>())
        }
    assertTrue(cloud.observeDeleted("owner", "home").first().isEmpty())
  }
}
