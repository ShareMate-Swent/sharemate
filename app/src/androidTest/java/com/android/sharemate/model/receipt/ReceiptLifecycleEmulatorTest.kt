// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.receipt

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.staging.AndroidInventorySubmissionStore
import com.android.sharemate.model.staging.ReceiptDetails
import com.android.sharemate.utils.FirebaseEmulator
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiptLifecycleEmulatorTest {
  @Test
  fun leaveEvictsLocalHistoryAndRejoinRestoresTheSameCloudReceipt() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val port =
        InstrumentationRegistry.getArguments().getString("receiptFirestorePort")?.toInt()
            ?: FirebaseEmulator.FIRESTORE_PORT
    val db = FirebaseFirestore.getInstance().apply { useEmulator(FirebaseEmulator.HOST, port) }
    val storage =
        FirebaseStorage.getInstance().apply {
          useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.STORAGE_PORT)
        }
    val owner = FirebaseEmulator.signInAs("receipt-lifecycle-${UUID.randomUUID()}")
    val lifecycle = ReceiptCacheLifecycle(context)
    val households =
        ReceiptHouseholdRepository(
            HouseholdRepositoryFirestore(db), lifecycle::evict, lifecycle::activate)
    val home = households.createHousehold("Receipt lifecycle", owner)
    val receipt =
        InventorySubmission(UUID.randomUUID().toString(), owner, home.id, emptyList(), draft = true)
    val photo = java.io.File(context.filesDir, "receipts/${receipt.id}.jpg")
    photo.parentFile!!.mkdirs()
    photo.writeText("receipt-photo")
    val entry =
        ReceiptCloudEntry(
            receipt,
            ReceiptDetails(
                receipt.id, owner, home.id, android.net.Uri.fromFile(photo).toString(), 1))
    val store = AndroidInventorySubmissionStore(context)
    store.save(receipt)
    store.saveDetails(entry.details)
    FirebaseReceiptCloudRepository(context, db, storage).upload(entry)
    val memberships = ReceiptMembershipObserver(db)
    assertTrue(
        withTimeout(TIMEOUT) { memberships.observe(owner).first { home.id in it } }
            .contains(home.id))

    households.leaveHousehold(owner)
    assertTrue(store.list(owner, home.id).isEmpty())
    assertFalse(photo.exists())
    assertTrue(withTimeout(TIMEOUT) { memberships.observe(owner).first { it.isEmpty() } }.isEmpty())
    households.joinHousehold(home.inviteCode, owner)
    val restored =
        withTimeout(TIMEOUT) {
          FirebaseReceiptCloudRepository(context, db, storage).observe(owner, home.id).first {
            it.isNotEmpty()
          }
        }
    assertEquals(receipt.id, restored.single().submission.id)
    assertEquals("receipt-photo", photo.readText())
    val newStore = AndroidInventorySubmissionStore(context)
    newStore.save(restored.single().submission)
    newStore.saveDetails(restored.single().details)
    assertEquals(listOf(receipt), newStore.list(owner, home.id))

    db.disableNetwork().await()
    try {
      assertNull(withTimeoutOrNull(OFFLINE_OBSERVATION) { memberships.observe(owner).first() })
      assertEquals(listOf(receipt), newStore.list(owner, home.id))
    } finally {
      db.enableNetwork().await()
    }
  }

  private companion object {
    const val TIMEOUT = 20_000L
    const val OFFLINE_OBSERVATION = 1_000L
  }
}
