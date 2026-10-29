package com.android.sharemate.ui.fridge

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
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
import com.android.sharemate.model.image.*
import com.android.sharemate.model.item.*
import com.android.sharemate.model.product.*
import com.android.sharemate.resources.C
import com.android.sharemate.ui.barcode.BarcodePhotoViewModel
import java.io.File
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
  private lateinit var photo: BarcodePhotoViewModel
  private lateinit var fridge: FridgeViewModel
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
  fun missingProductKeepsSamePhotoRetryableAndManualDraftIntact() {
    result = ProductLookupResult.NotFound
    showFormAndPhoto()
    val uri = photo.state.value.uri
    confirm()
    compose.waitUntil(10_000) { photo.state.value.failed && !photo.state.value.busy }
    compose
        .onAllNodesWithText(compose.activity.getString(R.string.barcode_lookup_not_found))
        .onLast()
        .assertExists()
    compose.runOnIdle {
      assertEquals("Manual food", fridge.uiState.value.itemName)
      assertEquals(uri, photo.state.value.uri)
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
    compose.onNodeWithTag("barcode_photo_cancel").performScrollTo().performClick()
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

  private fun confirm() {
    compose.onNodeWithTag("barcode_photo_confirm").performScrollTo().performClick()
  }

  private fun showFormAndPhoto() {
    compose.runOnIdle {
      photo = BarcodePhotoViewModel(AndroidBarcodePhotoStore(compose.activity), SavedStateHandle())
      fridge =
          FridgeViewModel(
              EmptyItems(),
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
              })
      owner.viewModelStore.put("add-item-barcode-photo", photo)
      owner.viewModelStore.put("fridge", fridge)
    }
    compose.setContent {
      CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        MaterialTheme { FridgeScreen(viewModel = fridge) }
      }
    }
    compose.onNodeWithTag(C.Tag.fridge_add_item).performClick()
    compose.runOnIdle {
      fridge.updateName("Manual food")
      fridge.updateQuantity("3")
      fridge.updateCategory("Dairy")
    }
    compose.onNodeWithTag("barcode_photo_open").performScrollTo().performClick()
    compose.runOnIdle {
      photo.capture { uri ->
        withContext(Dispatchers.IO) {
          val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
          File(Uri.parse(uri).path!!).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
          }
          bitmap.recycle()
        }
      }
    }
    compose.waitUntil(10_000) { photo.state.value.uri != null && !photo.state.value.busy }
  }

  private class EmptyItems : ItemRepository {
    override fun getPrivateItems(userId: String) = flowOf(emptyList<Item>())

    override fun getSharedItems(householdId: String) = flowOf(emptyList<Item>())

    override suspend fun addItem(item: Item): String? = error("Lookup must not save an item")

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
