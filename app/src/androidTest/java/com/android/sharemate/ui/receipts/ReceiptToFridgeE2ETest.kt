// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.sharemate.GroceryScanFixtures
import com.android.sharemate.R
import com.android.sharemate.ReceiptToFridgeTestActivity
import com.android.sharemate.model.barcode.MlKitBarcodeScanner
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.model.image.AndroidBarcodePhotoStore
import com.android.sharemate.model.image.FoodImageRepository
import com.android.sharemate.model.image.FoodImageSearchResult
import com.android.sharemate.model.image.FoodImageSelector
import com.android.sharemate.model.item.FirebaseItemRepository
import com.android.sharemate.model.item.ItemSharing
import com.android.sharemate.model.product.ProductCatalogRepository
import com.android.sharemate.model.product.ProductDetails
import com.android.sharemate.model.product.ProductLookupResult
import com.android.sharemate.model.receipt.ReceiptLine
import com.android.sharemate.model.receipt.ReceiptParser
import com.android.sharemate.model.receipt.ReceiptReader
import com.android.sharemate.model.staging.AndroidInventorySubmissionStore
import com.android.sharemate.model.staging.StagedItem
import com.android.sharemate.model.user.ExpirationPreferencesProvider
import com.android.sharemate.model.user.FirebaseExpirationPreferencesRepository
import com.android.sharemate.resources.C
import com.android.sharemate.ui.barcode.BarcodePhotoTestTags
import com.android.sharemate.ui.barcode.BarcodePhotoViewModel
import com.android.sharemate.ui.fridge.FridgeScreen
import com.android.sharemate.ui.fridge.FridgeUiState
import com.android.sharemate.ui.fridge.FridgeViewModel
import com.android.sharemate.ui.fridge.testMemberNameRepository
import com.android.sharemate.ui.fridge.testPseudoRepository
import com.android.sharemate.ui.staging.StagedItemEditTestTags
import com.android.sharemate.ui.staging.StagingTestTags
import com.android.sharemate.utils.FirebaseEmulator
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiptToFridgeE2ETest {
  @get:Rule val compose = createAndroidComposeRule<ReceiptToFridgeTestActivity>()
  private val models = ViewModelStore()

  @Before fun setUp() = FirebaseEmulator.clear()

  @After
  fun tearDown() {
    compose.runOnIdle { models.clear() }
    FirebaseEmulator.clear()
  }

  @Test
  fun barcodeAndReviewedReceiptItemsAppearInAnotherMembersFridgeExactlyOnce() {
    val receipt = ReceiptReader {
      ReceiptParser.parse(
          listOf(
              ReceiptLine("Article", 10, 10, 20),
              ReceiptLine("Quant.", 140, 10, 20),
              ReceiptLine("Prix", 200, 10, 20),
              ReceiptLine("Total", 340, 10, 20),
              ReceiptLine("Milk", 10, 30, 40),
              ReceiptLine("2", 149, 30, 40),
              ReceiptLine("2.40", 200, 30, 40),
              ReceiptLine("4.80", 340, 30, 40),
              ReceiptLine("Bread", 10, 50, 60),
              ReceiptLine("1", 149, 50, 60),
              ReceiptLine("3.10", 200, 50, 60),
              ReceiptLine("3.10", 340, 50, 60),
              ReceiptLine("Total CHF 7.90", 10, 70, 80)))
    }
    saveReceipt(
        receipt,
        writePhoto = { file ->
          val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
          try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
          } finally {
            bitmap.recycle()
          }
        },
        verifyDraft = { rows ->
          assertEquals(listOf("Milk" to 2, "Bread" to 1), rows.map { it.name to it.quantity })
          assertEquals(listOf(480L, 310L), rows.map { it.priceCents })
        },
        editReview = {
          compose.onNodeWithTag(StagingTestTags.item("0")).performClick()
          compose.onNodeWithTag(StagedItemEditTestTags.NAME).performTextReplacement("Oat milk")
          compose.onNodeWithTag(StagedItemEditTestTags.SAVE).performClick()
          compose.onNodeWithTag(StagingTestTags.delete("1")).performClick()
          compose.onNodeWithTag(StagingTestTags.ADD).performClick()
          compose.onNodeWithTag(StagedItemEditTestTags.NAME).performTextReplacement("Coffee")
          compose.onNodeWithTag(StagedItemEditTestTags.SAVE).performClick()
        },
        verifySavedRows = { rows ->
          assertEquals(listOf("Oat milk" to 2, "Coffee" to 1), rows.map { it.name to it.quantity })
        })
  }

  private fun saveReceipt(
      reader: ReceiptReader,
      writePhoto: (File) -> Unit,
      verifyDraft: (List<StagedItem>) -> Unit,
      editReview: () -> Unit,
      verifySavedRows: (List<StagedItem>) -> Unit,
  ) {
    val firestore = FirebaseEmulator.firestore
    val ownerId = FirebaseEmulator.signInAs("receipt-owner")
    val household = runBlocking {
      HouseholdRepositoryFirestore(firestore).createHousehold("Receipt household", ownerId)
    }
    val member = FirebaseEmulator.newSignedInApp("receipt-member-${UUID.randomUUID()}")
    val screen = mutableStateOf(Screen.OWNER_FRIDGE)
    try {
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      runBlocking {
        HouseholdRepositoryFirestore(member.firestore)
            .joinHousehold(household.inviteCode, member.uid)
      }
      val memberItems =
          FirebaseItemRepository(member.firestore).getFridgeItems(household.id, member.uid)
      val context = compose.activity
      val ownerFridge = createOwnerFridge(ownerId, household.id)
      val store = AndroidInventorySubmissionStore(context)
      val photo = BarcodePhotoViewModel(AndroidBarcodePhotoStore(context), SavedStateHandle())
      val save =
          InventorySaveViewModel(FirebaseItemRepository(firestore), store, ownerId, household.id)
      compose.runOnIdle {
        models.put("ownerFridge", ownerFridge)
        models.put("photo", photo)
        models.put("save", save)
      }
      compose.setContent {
        MaterialTheme {
          when (screen.value) {
            Screen.OWNER_FRIDGE -> FridgeScreen(ownerFridge)
            Screen.RECEIPTS -> ReceiptScanFlow(photo, reader, save)
            Screen.MEMBER_FRIDGE -> {
              val items by memberItems.collectAsState(emptyList())
              FridgeScreen(FridgeUiState(FridgeType.SHARED, items), expiryStatuses = emptyMap())
            }
          }
        }
      }
      GroceryScanFixtures(context).use { addSharedBarcodeItem(it.barcode(), ownerFridge) }
      compose.runOnIdle { screen.value = Screen.RECEIPTS }
      compose.waitUntil(TIMEOUT) { save.state.value.canStartScan }
      compose.onNodeWithText(context.getString(R.string.scan_receipt)).performClick()
      compose.runOnIdle {
        photo.capture { uri ->
          withContext(Dispatchers.IO) { writePhoto(File(checkNotNull(Uri.parse(uri).path))) }
        }
      }
      compose.waitUntil(TIMEOUT) { photo.state.value.uri != null && !photo.state.value.busy }
      compose.onNodeWithTag(BarcodePhotoTestTags.CONFIRM).performScrollTo().performClick()
      compose.waitUntil(TIMEOUT) { save.state.value.submission?.draft == true }
      verifyDraft(checkNotNull(save.state.value.submission).items)
      editReview()
      compose.onNodeWithTag(StagingTestTags.CONFIRM).performClick()
      compose.waitUntil(TIMEOUT) { save.state.value.phase == InventorySavePhase.CONFIRMED }
      compose.onNodeWithText(context.getString(R.string.inventory_save_success)).assertIsDisplayed()
      compose.onNodeWithText(context.getString(R.string.staging_title)).performClick()
      compose.onNodeWithTag(StagingTestTags.CONFIRM).assertIsNotEnabled()

      val savedRows = checkNotNull(save.state.value.submission).items
      verifySavedRows(savedRows)
      val expected = savedRows.map { it.name to it.quantity } + (OLIVE_OIL_NAME to 1)
      val visible = runBlocking {
        withTimeout(TIMEOUT) { memberItems.first { it.size >= expected.size } }
      }
      assertEquals(expected.size, visible.size)
      assertTrue(
          "Fridge did not receive the barcode product and reviewed receipt rows",
          expected.groupingBy { it }.eachCount() ==
              visible.groupingBy { it.name to it.quantity }.eachCount())
      assertTrue(visible.all { it.ownerId == ownerId && it.sharing == ItemSharing.SHARED })

      compose.runOnIdle { screen.value = Screen.MEMBER_FRIDGE }
      compose.waitUntil(TIMEOUT) {
        compose.onAllNodes(hasTestTag(C.Tag.fridge_item_list)).fetchSemanticsNodes().isNotEmpty()
      }
      for (name in expected.map { it.first }.distinct()) {
        compose.onNodeWithTag(C.Tag.fridge_item_list).performScrollToNode(hasText(name))
        compose.onNodeWithText(name).assertIsDisplayed()
      }
    } finally {
      compose.runOnIdle { screen.value = Screen.OWNER_FRIDGE }
      compose.waitForIdle()
      runBlocking { member.firestore.terminate().await() }
      member.app.delete()
    }
  }

  private fun createOwnerFridge(ownerId: String, householdId: String): FridgeViewModel {
    val firestore = FirebaseEmulator.firestore
    val productCatalog =
        object : ProductCatalogRepository {
          override suspend fun lookupByBarcode(barcode: String): ProductLookupResult =
              if (barcode == GroceryScanFixtures.OLIVE_OIL_EAN) {
                ProductLookupResult.Found(ProductDetails(barcode, OLIVE_OIL_NAME, null))
              } else ProductLookupResult.NotFound
        }
    val images =
        object : FoodImageRepository {
          override suspend fun searchByTitle(title: String) = FoodImageSearchResult.NotFound
        }
    return FridgeViewModel(
        itemRepository = FirebaseItemRepository(firestore),
        userId = ownerId,
        householdId = householdId,
        fridgeType = FridgeType.SHARED,
        foodImageSelector = FoodImageSelector(images),
        barcodeScanner = MlKitBarcodeScanner(compose.activity),
        productCatalogRepository = productCatalog,
        expirationPreferences =
            ExpirationPreferencesProvider(FirebaseExpirationPreferencesRepository(firestore)),
        pseudoRepository = testPseudoRepository(),
        memberNameRepository = testMemberNameRepository(),
        currentUserDisplayName = null)
  }

  private fun addSharedBarcodeItem(image: Uri, fridge: FridgeViewModel) {
    compose.onNodeWithTag(C.Tag.fridge_add_item).performClick()
    compose.onNodeWithTag("barcode_photo_open").performScrollTo().performClick()
    compose.runOnUiThread { compose.activity.galleryImage = image }
    compose.onNodeWithTag(BarcodePhotoTestTags.GALLERY).performScrollTo().performClick()
    compose.waitUntil(TIMEOUT) {
      compose
          .onAllNodes(hasTestTag(BarcodePhotoTestTags.CONFIRM) and isEnabled())
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    compose.onNodeWithTag(BarcodePhotoTestTags.CONFIRM).performScrollTo().performClick()
    compose.waitUntil(TIMEOUT) { fridge.uiState.value.itemName == OLIVE_OIL_NAME }
    compose.onNodeWithTag(C.Tag.fridge_share_switch).performScrollTo().performClick()
    compose.onNodeWithTag(C.Tag.fridge_save_item).performClick()
    compose.waitUntil(TIMEOUT) {
      fridge.uiState.value.items.any { it.name == OLIVE_OIL_NAME && it.isShared }
    }
  }

  private enum class Screen {
    OWNER_FRIDGE,
    RECEIPTS,
    MEMBER_FRIDGE,
  }

  private companion object {
    const val TIMEOUT = 90_000L
    const val OLIVE_OIL_NAME = "Olive oil"
  }
}
