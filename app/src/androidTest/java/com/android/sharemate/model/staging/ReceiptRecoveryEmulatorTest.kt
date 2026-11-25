// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.staging

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.sharemate.model.finance.FirebaseExpenseSubmissionRepository
import com.android.sharemate.model.finance.SharedExpense
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.model.item.*
import com.android.sharemate.utils.FirebaseEmulator
import com.google.firebase.functions.FirebaseFunctions
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiptRecoveryEmulatorTest {
  @Test
  fun restartBeforeSdkHandoffRecoversOnceAndServerTombstoneBlocksReplay() = runBlocking {
    withTimeout(TIMEOUT) {
      val context = InstrumentationRegistry.getInstrumentation().targetContext
      val db = FirebaseEmulator.firestore
      val owner = FirebaseEmulator.signInAs("receipt-recovery-${UUID.randomUUID()}")
      val home = HouseholdRepositoryFirestore(db).createHousehold("Recovery", owner)
      val id = UUID.randomUUID().toString()
      val receipt =
          InventorySubmission(
              id,
              owner,
              home.id,
              listOf(StagedItem("milk", "Milk", 1, 100)),
              expense = SharedExpense(id, home.id, owner, 100, mapOf(owner to 100L)))
      AndroidInventorySubmissionStore(context).save(receipt)
      // No addItems call: simulate the process stopping before the SDK accepted a batch.
      val restarted = AndroidInventorySubmissionStore(context)
      val items = FirebaseItemRepository(db)
      val expenses =
          FirebaseExpenseSubmissionRepository(
              FirebaseFunctions.getInstance("europe-west6").apply {
                useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.FUNCTIONS_PORT)
              })
      val sync = ReceiptSynchronization(restarted, items, expenses) {}
      assertTrue(sync.synchronize(owner, home.id, id, owner, false))
      assertTrue(sync.synchronize(owner, home.id, id, owner, false))
      assertEquals(
          ExpenseProgress.CONFIRMED, restarted.loadById(owner, home.id, id)?.expenseProgress)
      assertEquals(
          1,
          db.collection("items")
              .whereEqualTo("householdId", home.id)
              .whereEqualTo("ownerId", owner)
              .get()
              .await()
              .size())
      assertEquals(
          1,
          db.collection("households").document(home.id).collection("expenses").get().await().size())
      expenses.deleteReceipt(home.id, id)
      // Even a stale process without the local marker cannot recreate a deleted purchase.
      val replay = items.addItems(receipt) as ItemWrite.Queued
      assertTrue(replay.confirmation.await().isFailure)
      restarted.applyCloudDeletions(owner, home.id, setOf(id))
      assertTrue(sync.synchronize(owner, home.id, id, owner, false))
      assertFalse(db.collection("items").document(receipt.itemId(0)).get().await().exists())
    }
  }

  private companion object {
    const val TIMEOUT = 30_000L
  }
}
