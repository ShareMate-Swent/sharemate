// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.sharemate.model.barcode.BarcodeScanResult
import com.android.sharemate.model.barcode.BarcodeScanner
import com.android.sharemate.model.finance.*
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.model.item.FirebaseItemRepository
import com.android.sharemate.model.product.ProductCatalogRepository
import com.android.sharemate.model.product.ProductDetails
import com.android.sharemate.model.product.ProductLookupResult
import com.android.sharemate.model.staging.AndroidInventorySubmissionStore
import com.android.sharemate.ui.staging.PurchaseReview
import com.android.sharemate.ui.staging.StagingViewModel
import com.android.sharemate.utils.FirebaseEmulator
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Catalog fixtures feed the production review and trusted expense writer, without ledger seeding.
 */
@RunWith(AndroidJUnit4::class)
class BarcodePurchaseEmulatorTest {
  @Test
  fun repeatedBarcodePurchasesChargeExactReviewedTotalOnceAndReachSecondMember() = runBlocking {
    withTimeout(90_000) {
      FirebaseEmulator.clear()
      val owner = ViewModelStore()
      try {
        val db = FirebaseEmulator.firestore
        val alice = FirebaseEmulator.signInAs("barcode-purchase-alice")
        val households = HouseholdRepositoryFirestore(db)
        val home = households.createHousehold("Barcode purchase", alice)
        val bob = FirebaseEmulator.signInAs("barcode-purchase-bob")
        households.joinHousehold(home.inviteCode, bob)
        FirebaseEmulator.signInAs("barcode-purchase-alice")
        val members = mapOf(alice to "Alice", bob to "Bob")
        val functions = FirebaseFunctions.getInstance(FirebaseExpenseSubmissionRepository.REGION)
        functions.useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.FUNCTIONS_PORT)
        val expenses = FirebaseExpenseSubmissionRepository(functions)
        val items = FirebaseItemRepository(db)
        val journal =
            AndroidInventorySubmissionStore(
                InstrumentationRegistry.getInstrumentation().targetContext)
        val barcode =
            PurchaseBarcodeViewModel(
                BarcodeScanner { BarcodeScanResult.Found("7612345678901") },
                object : ProductCatalogRepository {
                  override suspend fun lookupByBarcode(barcode: String) =
                      ProductLookupResult.Found(ProductDetails(barcode, "Catalog milk", null))
                },
                SavedStateHandle())
        val staging = StagingViewModel(emptyList(), PurchaseReview.forUser(alice, members))
        val save =
            withContext(Dispatchers.Main) {
              owner.put("barcode", barcode)
              owner.put("staging", staging)
              InventorySaveViewModel(items, journal, alice, home.id, expenses = expenses).also {
                owner.put("save", it)
              }
            }
        save.state.first { it.phase == InventorySavePhase.READY }
        val reviewed =
            withContext(Dispatchers.Main) {
              barcode.startScan()
              val first = checkNotNull(barcode.read("fixture://milk"))
              assertNull(first.priceCents)
              staging.appendBarcode(first)
              assertNull(staging.confirmedPurchase())
              assertNull(barcode.read("fixture://milk"))
              staging.startEditing(first.id)
              staging.updateDraftName("Edited milk")
              staging.updateDraftQuantity("2")
              staging.updateDraftPrice("6.00")
              staging.saveDraft()
              barcode.startScan()
              val second = checkNotNull(barcode.read("fixture://milk"))
              assertNotEquals(first.id, second.id)
              staging.appendBarcode(second)
              staging.appendBarcode(second)
              staging.startEditing(second.id)
              staging.updateDraftPrice("4.00")
              staging.saveDraft()
              staging.toggleParticipant(alice)
              staging.toggleParticipant(bob)
              checkNotNull(staging.confirmedPurchase()).also {
                assertEquals(2, it.items.size)
                assertEquals(1000L, it.totalCents)
                assertEquals(mapOf(alice to 500L, bob to 500L), it.shares)
                assertTrue(save.confirmPurchase(it, members))
                assertFalse(save.confirmPurchase(it, members))
              }
            }
        val confirmed = save.state.first { it.phase == InventorySavePhase.CONFIRMED }.submission!!
        assertEquals(confirmed.expense, expenses.recordExpense(confirmed.expense!!))
        withContext(Dispatchers.Main) { owner.clear() }
        FirebaseEmulator.signInAs("barcode-purchase-bob")
        val inventory = items.getFridgeItems(home.id, bob).first { it.size == 2 }
        assertEquals(
            setOf(confirmed.itemId(0), confirmed.itemId(1)), inventory.map { it.id }.toSet())
        assertEquals(setOf("Edited milk", "Catalog milk"), inventory.map { it.name }.toSet())
        assertEquals(2, inventory.single { it.name == "Edited milk" }.quantity)
        val ledger =
            FirestoreHouseholdFinanceRepository(db, FirebaseSettlementRepository(functions))
        val snapshot =
            ledger.observeHouseholdFinances(home.id).first {
              it.isSynced && it.expenses.isNotEmpty()
            }
        assertEquals(listOf(confirmed.expense), snapshot.expenses)
        assertEquals(reviewed.totalCents, snapshot.expenses.single().totalAmountCents)
        assertEquals(
            -500L, BalanceCalculator.calculate(snapshot, listOf(alice, bob)).netBalancesCents[bob])
      } finally {
        withContext(Dispatchers.Main) { owner.clear() }
        FirebaseEmulator.clear()
      }
    }
  }
}
