// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.android.sharemate.R
import com.android.sharemate.ReceiptToFridgeTestActivity
import com.android.sharemate.model.barcode.BarcodeScanResult
import com.android.sharemate.model.barcode.BarcodeScanner
import com.android.sharemate.model.image.TemporaryPhotoStore
import com.android.sharemate.model.item.*
import com.android.sharemate.model.product.ProductCatalogRepository
import com.android.sharemate.model.product.ProductLookupResult
import com.android.sharemate.model.staging.InventorySubmissionStore
import com.android.sharemate.model.staging.StagedItem
import com.android.sharemate.ui.photo.PhotoPickerTestTags
import com.android.sharemate.ui.photo.PhotoPickerViewModel
import com.android.sharemate.ui.staging.StagedItemEditTestTags
import com.android.sharemate.ui.staging.StagingTestTags
import java.io.IOException
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BarcodePurchaseJournalFlowTest {
  @get:Rule val compose = createAndroidComposeRule<ReceiptToFridgeTestActivity>()

  @Test
  fun cancellingFirstScanPreservesExistingJournalDraft() {
    val original =
        InventorySubmission(
            "00000000-0000-0000-0000-000000000001",
            "alice",
            "home",
            listOf(StagedItem("milk", "Existing milk", 2, 600)),
            draft = true)
    val journal = Journal().apply { retained = original }
    withJournalFlow(journal) { _ ->
      compose.onNodeWithTag(PurchaseBarcodeTestTags.START).performClick()
      compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).performScrollTo().performClick()
      compose.onNodeWithText("Existing milk").assertExists()
      assertEquals(original, journal.retained)
      compose.onNodeWithTag(StagingTestTags.BACK).performClick()
      assertEquals(original.items, checkNotNull(journal.retained).items)
      compose.onNodeWithText(compose.activity.getString(R.string.staging_title)).performClick()
      compose.onNodeWithText("Existing milk").assertExists()
      compose.onNodeWithText("CHF 6.00").assertExists()
    }
  }

  private fun withJournalFlow(journal: Journal, verify: (InventorySaveViewModel) -> Unit) {
    val owners = ViewModelStore()
    val receipt = photo()
    val barcodePhoto = photo()
    val barcode = barcode()
    lateinit var save: InventorySaveViewModel
    compose.runOnIdle {
      save = InventorySaveViewModel(UnusedItems(), journal, "alice", "home")
      owners.put("save", save)
      owners.put("receipt", receipt)
      owners.put("photo", barcodePhoto)
      owners.put("barcode", barcode)
    }
    try {
      compose.setContent {
        MaterialTheme {
          ReceiptScanFlow(
              receipt, { emptyList() }, save, barcode = barcode, barcodePhoto = barcodePhoto)
        }
      }
      compose.waitUntil(10_000) { save.state.value.phase == InventorySavePhase.READY }
      verify(save)
    } finally {
      compose.runOnIdle { owners.clear() }
    }
  }

  @Test
  fun startAndBackJournalFailuresKeepReviewRecoverableUntilEditsAreDurable() {
    val journal = Journal()
    withJournalFlow(journal) { save ->
      compose.runOnIdle { journal.fail = true }
      compose.onNodeWithTag(PurchaseBarcodeTestTags.START).performClick()
      compose.waitUntil(10_000) { save.state.value.phase == InventorySavePhase.RECOVERY_FAILED }
      compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).assertDoesNotExist()
      assertNull(journal.retained)
      compose.runOnIdle { journal.fail = false }
      compose.onNodeWithTag(InventorySaveTestTags.RETRY).performClick()
      compose.waitUntil(10_000) { save.state.value.phase == InventorySavePhase.READY }
      compose.onNodeWithTag(PurchaseBarcodeTestTags.START).performClick()
      compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).performScrollTo().performClick()
      assertTrue(checkNotNull(journal.retained).draft)
      assertTrue(checkNotNull(journal.retained).items.isEmpty())
      compose.onNodeWithTag(StagingTestTags.ADD).performClick()
      compose.onNodeWithTag(StagedItemEditTestTags.NAME).performTextInput("Retained milk")
      compose.onNodeWithTag(StagedItemEditTestTags.PRICE).performTextInput("10.00")
      compose.onNodeWithTag(StagedItemEditTestTags.SAVE).performClick()
      compose.runOnIdle { journal.fail = true }
      compose.onNodeWithTag(StagingTestTags.BACK).performClick()
      compose.waitUntil(10_000) { save.state.value.phase == InventorySavePhase.RECOVERY_FAILED }
      compose.onNodeWithTag(InventorySaveTestTags.STATUS).assertExists()
      compose.onNodeWithTag(StagingTestTags.BACK).performClick()
      compose.onNodeWithTag(PurchaseBarcodeTestTags.START).assertDoesNotExist()
      compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
      compose.onNodeWithTag(PurchaseBarcodeTestTags.START).assertDoesNotExist()
      compose.runOnIdle { journal.fail = false }
      compose.onNodeWithTag(InventorySaveTestTags.RETRY).performClick()
      compose.waitUntil(10_000) { save.state.value.phase == InventorySavePhase.READY }
      compose.onNodeWithText("Retained milk").assertExists()
      compose.onNodeWithTag(StagingTestTags.BACK).performClick()
      compose.onNodeWithTag(PurchaseBarcodeTestTags.START).assertExists()
      val durable = checkNotNull(journal.retained)
      assertEquals("Retained milk", durable.items.single().name)
      assertEquals(1000L, durable.items.single().priceCents)
      compose.onNodeWithText(compose.activity.getString(R.string.staging_title)).performClick()
      compose.onNodeWithText("Retained milk").assertExists()
      compose.onNodeWithText("CHF 10.00").assertExists()
    }
  }

  @Test
  fun privateHouseholdCannotStartBarcodePurchase() {
    val owners = ViewModelStore()
    val receipt = photo()
    val photo = photo()
    val barcode = barcode()
    compose.runOnIdle {
      owners.put("receipt", receipt)
      owners.put("photo", photo)
      owners.put("barcode", barcode)
    }
    try {
      compose.setContent {
        MaterialTheme {
          ReceiptScanFlow(
              receipt,
              { emptyList() },
              null,
              sharedHousehold = false,
              barcode = barcode,
              barcodePhoto = photo)
        }
      }
      compose.onNodeWithTag(PurchaseBarcodeTestTags.START).assertIsNotEnabled()
      compose.onNodeWithTag(StagingTestTags.ADD).assertDoesNotExist()
    } finally {
      compose.runOnIdle { owners.clear() }
    }
  }

  private fun photo() =
      PhotoPickerViewModel(
          object : TemporaryPhotoStore {
            override suspend fun createCapture(): String = error("unused")

            override suspend fun importImage(uri: String): String = error("unused")

            override suspend fun validate(uri: String) = Unit

            override suspend fun delete(uri: String) = Unit
          },
          SavedStateHandle())

  private fun barcode() =
      PurchaseBarcodeViewModel(
          BarcodeScanner { BarcodeScanResult.NotFound },
          object : ProductCatalogRepository {
            override suspend fun lookupByBarcode(barcode: String) = ProductLookupResult.NotFound
          },
          SavedStateHandle())

  private class Journal : InventorySubmissionStore {
    var retained: InventorySubmission? = null
    var fail = false

    override suspend fun load(ownerId: String, householdId: String) = retained

    override suspend fun save(submission: InventorySubmission) {
      if (fail) throw IOException("journal unavailable")
      retained = submission
    }
  }

  private class UnusedItems : ItemRepository {
    override suspend fun getFridgeItemsFromServer(householdId: String, userId: String): List<Item> =
        error("Server inventory reads are not used in this test")

    override fun getFridgeItems(householdId: String, userId: String) = flowOf(emptyList<Item>())

    override suspend fun addItem(item: Item): String? = error("unexpected write")

    override fun addItems(submission: InventorySubmission): ItemWrite = error("unexpected write")

    override fun observeSubmission(submission: InventorySubmission) =
        flowOf(InventorySubmissionStatus.MISSING)

    override suspend fun updateItemImageIfAbsent(
        itemId: String,
        expectedName: String,
        image: ItemImage
    ): ItemImageUpdateResult = error("unexpected write")

    override suspend fun deleteItem(itemId: String): Boolean = error("unexpected write")

    override fun updateItem(itemId: String, edit: ItemEdit): ItemWrite = error("unexpected write")

    override fun setItemSharing(itemId: String, sharing: ItemSharing): ItemWrite =
        error("unexpected write")

    override fun setItemStatus(itemId: String, status: ItemStatus): ItemWrite =
        error("unexpected write")

    override fun deleteItemQueued(itemId: String): ItemWrite = error("unexpected write")
  }
}
