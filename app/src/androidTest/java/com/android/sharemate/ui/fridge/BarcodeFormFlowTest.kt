// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.fridge

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.R
import com.android.sharemate.model.barcode.BarcodeScanResult
import com.android.sharemate.model.barcode.BarcodeScanner
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.image.*
import com.android.sharemate.model.item.*
import com.android.sharemate.model.item.ItemCategory
import com.android.sharemate.model.product.*
import com.android.sharemate.model.user.ExpirationPreferencesProvider
import com.android.sharemate.model.user.FirebaseExpirationPreferencesRepository
import com.android.sharemate.resources.C
import com.android.sharemate.ui.barcode.BarcodeFormTestTags
import com.android.sharemate.ui.navigation.AddItemMethod
import com.android.sharemate.ui.photo.PhotoPickerTestTags
import com.android.sharemate.ui.photo.PhotoPickerViewModel
import com.android.sharemate.utils.FirebaseEmulator
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production screen callback with both real ViewModels and a private photo file. */
@RunWith(AndroidJUnit4::class)
class BarcodeFormFlowTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val owner =
      object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
      }
  private lateinit var photo: PhotoPickerViewModel
  private lateinit var itemPhoto: PhotoPickerViewModel
  private lateinit var fridge: FridgeViewModel
  private val items = EmptyItems()
  // Whether the add button's host is composed; false stands for an Activity being recreated.
  private val hostShown = mutableStateOf(true)
  private var result: ProductLookupResult =
      ProductLookupResult.Found(ProductDetails("123", "Milk", "Farm"))
  private val scanned = mutableListOf<String>()

  @After
  fun tearDown() {
    compose.runOnIdle { owner.viewModelStore.clear() }
  }

  @Test
  fun confirmedPhotoPrefillsEditableNameThroughProductionScreen() {
    showFormAndPhoto()
    confirm()
    compose.waitUntil(10_000) { photo.state.value.confirmed && !photo.state.value.busy }
    compose.onNodeWithTag(C.Tag.fridge_name_input).assertTextContains("Milk — Farm")
    compose.onNodeWithTag(C.Tag.fridge_quantity_input).assertTextContains("3")
    compose.onNodeWithTag(C.Tag.fridge_category_input).assertTextContains("Dairy")
    compose
        .onNodeWithTag(C.Tag.fridge_name_input)
        .performScrollTo()
        .performTextReplacement("My milk")
    compose.runOnIdle {
      assertEquals("My milk", fridge.uiState.value.itemName)
      assertEquals(1, scanned.size)
      assertFalse(photo.state.value.visible)
    }
  }

  @Test
  fun barcodeScanKeepsTheConfirmedItemPhoto() {
    showForm()
    compose
        .onNodeWithText(compose.activity.getString(R.string.personal_photo_add))
        .performScrollTo()
        .performClick()
    capture(itemPhoto, Color.GREEN)
    confirm()
    val photos = checkNotNull(fridge.personalPhotos)
    compose.waitUntil(10_000) { photos.draft.value.file != null && !itemPhoto.state.value.visible }
    val original = checkNotNull(photos.draft.value.file)
    val originalBytes = original.readBytes()
    compose.runOnIdle { assertTrue(scanned.isEmpty()) }

    openBarcodePhoto()
    val barcodeUri = checkNotNull(photo.state.value.selection).uri
    confirm()
    compose.waitUntil(10_000) { photo.state.value.confirmed && !photo.state.value.busy }
    compose.onNodeWithTag(C.Tag.fridge_name_input).assertTextContains("Milk — Farm")
    compose.runOnIdle {
      assertEquals(listOf(barcodeUri), scanned)
      assertEquals(original, photos.draft.value.file)
      assertArrayEquals(originalBytes, original.readBytes())
    }
  }

  @Test
  fun missingProductKeepsSamePhotoRetryableAndManualDraftIntact() {
    result = ProductLookupResult.NotFound
    showFormAndPhoto()
    val uri = photo.state.value.selection?.uri
    confirm()
    compose.waitUntil(10_000) { photo.state.value.failed && !photo.state.value.busy }
    compose
        .onAllNodesWithText(compose.activity.getString(R.string.barcode_lookup_not_found))
        .onLast()
        .assertExists()
    compose.runOnIdle {
      assertEquals("Manual food", fridge.uiState.value.itemName)
      assertEquals(uri, photo.state.value.selection?.uri)
      assertFalse(photo.state.value.confirmed)
      result = ProductLookupResult.Found(ProductDetails("123", "Retry milk", null))
    }
    confirm()
    compose.waitUntil(10_000) { photo.state.value.confirmed && !photo.state.value.busy }
    compose.onNodeWithTag(C.Tag.fridge_name_input).assertTextContains("Retry milk")
    compose.runOnIdle { assertEquals(listOf(uri, uri), scanned) }
  }

  @Test
  fun networkFailureExplainsManualFallbackAndDoesNotEraseFields() {
    result = ProductLookupResult.Failure(ProductLookupResult.Reason.NETWORK)
    showFormAndPhoto()
    confirm()
    compose.waitUntil(10_000) { photo.state.value.failed && !photo.state.value.busy }
    compose
        .onAllNodesWithText(compose.activity.getString(R.string.barcode_lookup_failed))
        .onLast()
        .assertExists()
    compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).performScrollTo().performClick()
    compose.onNodeWithTag(C.Tag.fridge_name_input).assertTextContains("Manual food")
    compose.onNodeWithTag(C.Tag.fridge_quantity_input).assertTextContains("3")
    compose.onNodeWithTag(C.Tag.fridge_category_input).assertTextContains("Dairy")
    compose
        .onNodeWithTag(C.Tag.fridge_name_input)
        .performScrollTo()
        .performTextReplacement("Manual correction")
    compose.onNodeWithTag(C.Tag.fridge_save_item).assertIsEnabled()
    compose.runOnIdle { assertEquals("Manual correction", fridge.uiState.value.itemName) }
  }

  @Test
  fun barcodeOptionOpensOverTheOpenTabAndCancellingThePhotoLeavesTheForm() {
    val handled = mutableListOf<AddItemMethod>()
    var saved = 0
    showFridge(AddItemMethod.BARCODE, overOtherTab = true, handled = { handled += it }) { saved++ }
    compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).assertExists()
    // Only the camera shows: the form waits for a photo, so it never flashes behind it.
    compose.onNodeWithTag(C.Tag.fridge_add_dialog).assertDoesNotExist()
    // The form opens over the open tab: the fridge never shows.
    compose.onNodeWithTag(C.Tag.fridge_screen_container).assertDoesNotExist()
    compose.runOnIdle {
      assertEquals(listOf(AddItemMethod.BARCODE), handled)
      assertTrue(photo.state.value.visible)
      assertFalse(fridge.uiState.value.scanBarcodeOnOpen)
    }
    compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).performClick()
    compose.onNodeWithTag(C.Tag.fridge_add_dialog).assertDoesNotExist()
    compose.runOnIdle {
      assertFalse(fridge.uiState.value.isAddItemDialogOpen)
      assertEquals(0, saved)
    }
  }

  @Test
  fun barcodeOptionKeepsTheFormAfterAFailedLookup() {
    result = ProductLookupResult.NotFound
    showFridge(AddItemMethod.BARCODE, overOtherTab = true)
    capture(photo, Color.BLACK)
    confirm()
    compose.waitUntil(10_000) { photo.state.value.failed && !photo.state.value.busy }
    // The camera stays in front of the form, which waits behind it.
    compose.onNodeWithTag(C.Tag.fridge_add_dialog).assertDoesNotExist()
    // The lookup failed: leaving the photo keeps the form, to type the item in instead.
    compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).performScrollTo().performClick()
    compose.onNodeWithTag(C.Tag.fridge_name_input).assertExists()
  }

  @Test
  fun manualOptionOverAnotherTabOpensTheFridgeOnlyOnceTheItemIsSaved() {
    var saved = 0
    showFridge(AddItemMethod.MANUAL, overOtherTab = true) { saved++ }
    compose.onNodeWithTag(C.Tag.fridge_name_input).assertExists()
    compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).assertDoesNotExist()
    compose.runOnIdle {
      assertFalse(photo.state.value.visible)
      fridge.updateName("Milk")
    }
    compose.onNodeWithTag(C.Tag.fridge_save_item).performClick()
    compose.waitUntil(10_000) { !fridge.uiState.value.isAddItemDialogOpen }
    compose.runOnIdle {
      assertEquals(listOf("Milk"), items.savedNames)
      assertEquals(1, saved)
    }
  }

  @Test
  fun aSaveEndingWhileTheHostIsRecreatedStillOpensTheFridge() {
    var saved = 0
    items.saveGate = CompletableDeferred()
    showFridge(AddItemMethod.MANUAL, overOtherTab = true) { saved++ }
    compose.runOnIdle { fridge.updateName("Milk") }
    compose.onNodeWithTag(C.Tag.fridge_save_item).performClick()
    compose.waitUntil(10_000) { fridge.uiState.value.isSaving }
    // The host leaves, as during an Activity recreation, and the save ends meanwhile.
    compose.runOnIdle { hostShown.value = false }
    compose.runOnIdle { checkNotNull(items.saveGate).complete(Unit) }
    compose.waitUntil(10_000) { !fridge.uiState.value.isAddItemDialogOpen }
    compose.runOnIdle { assertEquals(0, saved) }

    compose.runOnIdle { hostShown.value = true }
    compose.waitUntil(10_000) { saved == 1 }
    compose.runOnIdle {
      assertEquals(listOf("Milk"), items.savedNames)
      assertFalse(fridge.uiState.value.addButtonItemSaved)
    }
  }

  @Test
  fun manualOptionOnTheFridgeTabOpensTheFridgeForm() {
    val handled = mutableListOf<AddItemMethod>()
    showFridge(AddItemMethod.MANUAL, handled = { handled += it })
    compose.onNodeWithTag(C.Tag.fridge_screen_container).assertExists()
    compose.onNodeWithTag(C.Tag.fridge_name_input).assertExists()
    compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).assertDoesNotExist()
    compose.runOnIdle { assertEquals(listOf(AddItemMethod.MANUAL), handled) }
  }

  private fun confirm() {
    compose.onNodeWithTag(PhotoPickerTestTags.CONFIRM).performScrollTo().performClick()
  }

  private fun showFormAndPhoto() {
    showForm()
    openBarcodePhoto()
  }

  private fun showForm() {
    showFridge()
    compose.onNodeWithTag(C.Tag.fridge_add_item).performClick()
    compose.runOnIdle {
      fridge.updateName("Manual food")
      fridge.updateQuantity("3")
      fridge.updateCategory(ItemCategory.DAIRY)
    }
  }

  /**
   * Shows the fridge with real ViewModels and no form open, as the fridge tab, or, with
   * [overOtherTab], only the host of the add button's form, as on another tab. The host gets the
   * add-button [request], reports it to [handled] and a saved item to [saved].
   */
  private fun showFridge(
      request: AddItemMethod? = null,
      overOtherTab: Boolean = false,
      handled: (AddItemMethod) -> Unit = {},
      saved: () -> Unit = {},
  ) {
    compose.runOnIdle {
      photo = PhotoPickerViewModel(AndroidTemporaryPhotoStore(compose.activity), SavedStateHandle())
      itemPhoto =
          PhotoPickerViewModel(AndroidTemporaryPhotoStore(compose.activity), SavedStateHandle())
      fridge =
          FridgeViewModel(
              items,
              "user",
              "household",
              FoodImageSelector(
                  object : FoodImageRepository {
                    override suspend fun searchByTitle(title: String) =
                        FoodImageSearchResult.NotFound
                  }),
              BarcodeScanner { uri ->
                assertTrue(File(Uri.parse(uri).path!!).isFile)
                scanned += uri
                BarcodeScanResult.Found("123")
              },
              object : ProductCatalogRepository {
                override suspend fun lookupByBarcode(barcode: String): ProductLookupResult {
                  assertEquals("123", barcode)
                  return result
                }
              },
              photoStorage =
                  object : PersonalPhotoRepository {
                    override suspend fun load(
                        reference: PersonalPhotoReference
                    ): Result<ByteArray> = error("Unexpected load")

                    override suspend fun upload(
                        itemId: String,
                        source: File
                    ): Result<PersonalPhotoReference> = error("Unexpected upload")

                    override suspend fun delete(reference: PersonalPhotoReference): Result<Unit> =
                        error("Unexpected delete")

                    override suspend fun deleteAttached(itemId: String): Result<Unit> =
                        error("Unexpected delete")
                  },
              photoFiles = AndroidPersonalPhotoFiles(compose.activity),
              expirationPreferences =
                  ExpirationPreferencesProvider(
                      FirebaseExpirationPreferencesRepository(FirebaseEmulator.firestore)),
              fridgeType = FridgeType.SHARED,
              memberNameRepository = testMemberNameRepository(),
              currentUserDisplayName = null,
              pseudoRepository = testPseudoRepository(),
          )
      owner.viewModelStore.put("add-item-personal-photo", itemPhoto)
      owner.viewModelStore.put("add-item-barcode-photo", photo)
      owner.viewModelStore.put("fridge", fridge)
    }
    compose.setContent {
      CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        MaterialTheme {
          if (!overOtherTab) FridgeScreen(viewModel = fridge)
          if (hostShown.value) AddItemHost(fridge, request, handled, overOtherTab, onSaved = saved)
        }
      }
    }
  }

  private fun openBarcodePhoto() {
    compose.onNodeWithTag(BarcodeFormTestTags.OPEN).performScrollTo().performClick()
    capture(photo, Color.BLACK)
  }

  private fun capture(picker: PhotoPickerViewModel, color: Int) {
    compose.runOnIdle {
      picker.capture { uri ->
        withContext(Dispatchers.IO) {
          val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
          bitmap.eraseColor(color)
          File(Uri.parse(uri).path!!).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
          }
          bitmap.recycle()
        }
      }
    }
    compose.waitUntil(10_000) { picker.state.value.selection != null && !picker.state.value.busy }
  }

  /**
   * No stored items; records the names of the items saved, which only the save tests do. A save
   * waits for [saveGate] when it is set.
   */
  private class EmptyItems : ItemRepository {
    val savedNames = mutableListOf<String>()
    var saveGate: CompletableDeferred<Unit>? = null

    override suspend fun getFridgeItemsFromServer(householdId: String, userId: String): List<Item> =
        error("Server inventory reads are not used in this test")

    override fun getFridgeItems(householdId: String, userId: String) = flowOf(emptyList<Item>())

    override fun addItems(submission: InventorySubmission): ItemWrite =
        error("Batch saves are not used in this test")

    override fun observeSubmission(
        submission: InventorySubmission
    ): kotlinx.coroutines.flow.Flow<InventorySubmissionStatus> =
        error("Submission observation is not used in this test")

    override suspend fun addItem(item: Item): String? {
      saveGate?.await()
      savedNames += item.name
      return "saved-${savedNames.size}"
    }

    override suspend fun updateItemImageIfAbsent(
        itemId: String,
        expectedName: String,
        image: ItemImage
    ) = ItemImageUpdateResult.Ignored

    override suspend fun deleteItem(itemId: String) = false

    override fun updateItem(itemId: String, edit: ItemEdit) =
        ItemWrite.Rejected(IllegalStateException())

    override fun setItemStatus(itemId: String, status: ItemStatus) =
        ItemWrite.Rejected(IllegalStateException())

    override fun setItemSharing(itemId: String, sharing: ItemSharing) =
        ItemWrite.Rejected(IllegalStateException())

    override fun deleteItemQueued(itemId: String) = ItemWrite.Rejected(IllegalStateException())
  }
}
