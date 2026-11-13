// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.fridge

import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.barcode.BarcodeScanResult
import com.android.sharemate.model.barcode.BarcodeScanner
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.image.FoodImageRepository
import com.android.sharemate.model.image.FoodImageSearchResult
import com.android.sharemate.model.image.FoodImageSelector
import com.android.sharemate.model.item.FakeItemRepository
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemSharing
import com.android.sharemate.model.product.ProductCatalogRepository
import com.android.sharemate.model.product.ProductDetails
import com.android.sharemate.model.product.ProductLookupResult
import com.android.sharemate.model.text.TextLimits
import com.android.sharemate.model.user.ExpirationPreferencesProvider
import com.android.sharemate.model.user.FakeExpirationPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Length limits of the item form: typed and pasted texts are cut, older long values are refused.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FridgeTextLimitsTest {
  private val owner = ViewModelStore()
  private val items = FakeItemRepository()
  private var product = ProductDetails("0012345678905", "Milk", "Farm")
  private lateinit var vm: FridgeViewModel

  @Before
  fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
    vm =
        FridgeViewModel(
            items,
            USER,
            HOUSEHOLD,
            FoodImageSelector(
                object : FoodImageRepository {
                  override suspend fun searchByTitle(title: String) = FoodImageSearchResult.NotFound
                }),
            BarcodeScanner { BarcodeScanResult.Found(product.barcode) },
            object : ProductCatalogRepository {
              override suspend fun lookupByBarcode(barcode: String) =
                  ProductLookupResult.Found(product)
            },
            expirationPreferences =
                ExpirationPreferencesProvider(FakeExpirationPreferencesRepository()),
            fridgeType = FridgeType.SHARED)
    owner.put("fridge", vm)
  }

  @After
  fun tearDown() {
    owner.clear()
    Dispatchers.resetMain()
  }

  @Test
  fun pastedNameIsCutToTheLimit() = runTest {
    vm.openAddItemDialog()
    vm.updateName(SCRIPT.repeat(SCRIPT_REPEATS))
    assertEquals(TextLimits.ITEM_NAME, vm.uiState.value.itemName.length)
    assertEquals(
        SCRIPT.repeat(SCRIPT_REPEATS).take(TextLimits.ITEM_NAME), vm.uiState.value.itemName)
  }

  @Test
  fun nameAtTheLimitIsKeptAndSaved() = runTest {
    val name = "a".repeat(TextLimits.ITEM_NAME)
    vm.openAddItemDialog()
    vm.updateName(name)
    assertEquals(name, vm.uiState.value.itemName)
    vm.saveItem()
    runCurrent()
    assertEquals(name, items.addedItems.single().name)
  }

  @Test
  fun nameJustOverTheLimitLosesOnlyTheExtraCodePoint() = runTest {
    vm.openAddItemDialog()
    vm.updateName("a".repeat(TextLimits.ITEM_NAME + 1))
    assertEquals("a".repeat(TextLimits.ITEM_NAME), vm.uiState.value.itemName)
  }

  @Test
  fun emojiAreCountedAsOneCharacterAndNeverSplit() = runTest {
    vm.openAddItemDialog()
    vm.updateName("🍎".repeat(TextLimits.ITEM_NAME + 10))
    assertEquals("🍎".repeat(TextLimits.ITEM_NAME), vm.uiState.value.itemName)
    vm.updateCategory("🥛".repeat(TextLimits.ITEM_CATEGORY + 10))
    assertEquals("🥛".repeat(TextLimits.ITEM_CATEGORY), vm.uiState.value.itemCategory)
  }

  @Test
  fun pastedCategoryIsCutToTheLimit() = runTest {
    vm.openAddItemDialog()
    vm.updateCategory("c".repeat(TextLimits.ITEM_CATEGORY + 1))
    assertEquals("c".repeat(TextLimits.ITEM_CATEGORY), vm.uiState.value.itemCategory)
    vm.updateCategory("c".repeat(TextLimits.ITEM_CATEGORY))
    assertEquals("c".repeat(TextLimits.ITEM_CATEGORY), vm.uiState.value.itemCategory)
  }

  @Test
  fun typingAfterAnErrorClearsItEvenWhenTheTextIsCut() = runTest {
    vm.openAddItemDialog()
    vm.saveItem()
    assertEquals(FridgeFormError.NAME_REQUIRED, vm.uiState.value.formError)
    vm.updateName(SCRIPT.repeat(SCRIPT_REPEATS))
    assertNull(vm.uiState.value.formError)
  }

  @Test
  fun scannedProductNameIsCutToTheLimit() = runTest {
    product = ProductDetails(product.barcode, "p".repeat(TextLimits.ITEM_NAME * 2), "Farm")
    vm.openAddItemDialog()
    vm.confirmBarcodeImage("file:///photo")
    assertEquals("p".repeat(TextLimits.ITEM_NAME), vm.uiState.value.itemName)
    assertNull(vm.uiState.value.barcodeLookupError)
  }

  @Test
  fun scannedProductNameWithinTheLimitIsUnchanged() = runTest {
    vm.openAddItemDialog()
    vm.confirmBarcodeImage("file:///photo")
    assertEquals("Milk — Farm", vm.uiState.value.itemName)
  }

  @Test
  fun oldLongNameMustBeShortenedBeforeAnEditIsSaved() = runTest {
    items.emitItems(listOf(legacy(name = "n".repeat(LEGACY_LENGTH))))
    runCurrent()
    vm.openEditItemDialog(LEGACY_ID)
    runCurrent()
    assertEquals("n".repeat(LEGACY_LENGTH), vm.uiState.value.itemName)
    vm.saveItem()
    runCurrent()
    assertEquals(FridgeFormError.NAME_TOO_LONG, vm.uiState.value.formError)
    assertTrue(items.edits.isEmpty())
    vm.updateName(vm.uiState.value.itemName)
    vm.saveItem()
    runCurrent()
    assertEquals("n".repeat(TextLimits.ITEM_NAME), items.edits.single().second.name)
    assertNull(vm.uiState.value.formError)
  }

  @Test
  fun oldLongCategoryMustBeShortenedBeforeAnEditIsSaved() = runTest {
    items.emitItems(listOf(legacy(category = "c".repeat(LEGACY_LENGTH))))
    runCurrent()
    vm.openEditItemDialog(LEGACY_ID)
    runCurrent()
    vm.saveItem()
    runCurrent()
    assertEquals(FridgeFormError.CATEGORY_TOO_LONG, vm.uiState.value.formError)
    assertTrue(items.edits.isEmpty())
    vm.updateCategory("Dairy")
    vm.saveItem()
    runCurrent()
    assertEquals("Dairy", items.edits.single().second.category)
  }

  @Test
  fun validationReportsTheNameBeforeTheCategoryAndAcceptsTheLimits() {
    val name = "a".repeat(TextLimits.ITEM_NAME)
    val category = "c".repeat(TextLimits.ITEM_CATEGORY)
    assertNull(validateItemTexts(name, category))
    assertNull(validateItemTexts("", ""))
    assertEquals(FridgeFormError.NAME_TOO_LONG, validateItemTexts(name + "a", category))
    assertEquals(FridgeFormError.CATEGORY_TOO_LONG, validateItemTexts(name, category + "c"))
    assertEquals(FridgeFormError.NAME_TOO_LONG, validateItemTexts(name + "a", category + "c"))
    assertNull(
        validateItemTexts("🍎".repeat(TextLimits.ITEM_NAME), "🥛".repeat(TextLimits.ITEM_CATEGORY)))
  }

  private fun legacy(name: String = "Old", category: String? = null) =
      Item(
          id = LEGACY_ID,
          name = name,
          ownerId = USER,
          householdId = HOUSEHOLD,
          category = category,
          sharing = ItemSharing.SHARED)

  private companion object {
    const val USER = "user"
    const val HOUSEHOLD = "household"
    const val LEGACY_ID = "legacy"
    const val LEGACY_LENGTH = 200
    const val SCRIPT = "According to all known laws of aviation, there is no way a bee should fly. "
    const val SCRIPT_REPEATS = 50
  }
}
