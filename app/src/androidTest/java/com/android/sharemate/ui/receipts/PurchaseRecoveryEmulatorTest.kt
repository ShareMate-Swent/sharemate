// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.sharemate.model.finance.*
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.model.item.FirebaseItemRepository
import com.android.sharemate.model.staging.AndroidInventorySubmissionStore
import com.android.sharemate.model.staging.StagedItem
import com.android.sharemate.ui.staging.ReviewedPurchase
import com.android.sharemate.utils.FirebaseEmulator
import com.google.firebase.functions.FirebaseFunctions
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The retained journal bridges offline inventory, owner recreation and the trusted expense writer.
 */
@RunWith(AndroidJUnit4::class)
class PurchaseRecoveryEmulatorTest {
  @Test
  fun offlineRestartCreatesOnePurchaseVisibleToSecondMemberAndSettlement() = runBlocking {
    withTimeout(90_000) {
      FirebaseEmulator.clear()
      val db = FirebaseEmulator.firestore
      val owner = ViewModelStore()
      try {
        val alice = FirebaseEmulator.signInAs("purchase-alice")
        val households = HouseholdRepositoryFirestore(db)
        val home = households.createHousehold("Receipt recovery", alice)
        val bob = FirebaseEmulator.signInAs("purchase-bob")
        households.joinHousehold(home.inviteCode, bob)
        FirebaseEmulator.signInAs("purchase-alice")
        val functions = FirebaseFunctions.getInstance(FirebaseExpenseSubmissionRepository.REGION)
        functions.useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.FUNCTIONS_PORT)
        val expenses = FirebaseExpenseSubmissionRepository(functions)
        val items = FirebaseItemRepository(db)
        val journal =
            AndroidInventorySubmissionStore(
                InstrumentationRegistry.getInstrumentation().targetContext)
        suspend fun model() =
            withContext(Dispatchers.Main) {
              InventorySaveViewModel(items, journal, alice, home.id, expenses = expenses).also {
                owner.put("save", it)
              }
            }
        val first = model()
        first.state.first { it.phase == InventorySavePhase.READY }
        db.disableNetwork().await()
        val reviewed =
            ReviewedPurchase(
                listOf(StagedItem("milk", "Edited milk", 2, 1000)),
                alice,
                1000,
                mapOf(alice to 500L, bob to 500L))
        withContext(Dispatchers.Main) {
          assertTrue(first.confirmPurchase(reviewed, mapOf(alice to "Alice", bob to "Bob")))
          assertFalse(first.confirmPurchase(reviewed, mapOf(alice to "Alice", bob to "Bob")))
        }
        val pending = first.state.first { it.phase == InventorySavePhase.PENDING }.submission!!
        assertNotNull(journal.load(alice, home.id)?.expense)
        assertFalse(pending.inventoryConfirmed)
        withContext(Dispatchers.Main) { owner.clear() }
        val recovered = model()
        recovered.state.first { it.phase == InventorySavePhase.PENDING }
        db.enableNetwork().await()
        val confirmed =
            recovered.state.first { it.phase == InventorySavePhase.CONFIRMED }.submission!!
        assertEquals(pending.id, confirmed.id)
        assertEquals(confirmed.expense, expenses.recordExpense(confirmed.expense!!))
        withContext(Dispatchers.Main) { owner.clear() }
        FirebaseEmulator.signInAs("purchase-bob")
        val inventory = items.getFridgeItems(home.id, bob).first { it.isNotEmpty() }
        assertEquals(listOf(confirmed.itemId(0)), inventory.map { it.id })
        assertEquals("Edited milk", inventory.single().name)
        assertEquals(2, inventory.single().quantity)
        val settlements = FirebaseSettlementRepository(functions)
        val ledger = FirestoreHouseholdFinanceRepository(db, settlements)
        val snapshot =
            ledger.observeHouseholdFinances(home.id).first {
              it.isSynced && it.expenses.isNotEmpty()
            }
        assertEquals(listOf(confirmed.expense), snapshot.expenses)
        assertEquals(
            -500L, BalanceCalculator.calculate(snapshot, listOf(alice, bob)).netBalancesCents[bob])
        settlements.recordSettlement(
            Settlement(UUID.randomUUID().toString(), home.id, bob, alice, 500))
        val settled =
            ledger.observeHouseholdFinances(home.id).first {
              it.isSynced && it.settlements.isNotEmpty()
            }
        assertTrue(
            BalanceCalculator.calculate(settled, listOf(alice, bob)).netBalancesCents.values.all {
              it == 0L
            })
      } finally {
        withContext(Dispatchers.Main) { owner.clear() }
        db.enableNetwork().await()
        FirebaseEmulator.clear()
      }
    }
  }
}
