// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.R
import com.android.sharemate.model.barcode.BarcodeScanResult
import com.android.sharemate.model.barcode.BarcodeScanner
import com.android.sharemate.model.image.FoodImageRepository
import com.android.sharemate.model.image.FoodImageSearchResult
import com.android.sharemate.model.image.FoodImageSelector
import com.android.sharemate.model.item.ExpirationDates
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemEdit
import com.android.sharemate.model.item.ItemImage
import com.android.sharemate.model.item.ItemImageUpdateResult
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.item.ItemStatus
import com.android.sharemate.model.item.ItemWrite
import com.android.sharemate.resources.C
import com.android.sharemate.ui.theme.SampleAppTheme
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualItemCreationTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun emptyNameIsRejectedAndValidItemIsRendered() {
    val repository = TestItemRepository()
    val viewModel = showManualEntryScreen(repository)
    val expirationDate = LocalDate.now().plusDays(30)
    val storedExpirationDate = ExpirationDates.toDate(expirationDate)
    composeTestRule
        .onNodeWithTag(C.Tag.fridge_add_item)
        .assertIsDisplayed()
        .assertIsEnabled()
        .performClick()
    composeTestRule.onNodeWithTag(C.Tag.fridge_add_dialog).assertIsDisplayed()
    composeTestRule
        .onNodeWithTag(C.Tag.fridge_quantity_input)
        .assertIsDisplayed()
        .assertTextContains("1")
    composeTestRule.onNodeWithTag(C.Tag.fridge_save_item).assertIsEnabled().performClick()
    composeTestRule
        .onNodeWithTag(C.Tag.fridge_form_error)
        .performScrollTo()
        .assertIsDisplayed()
        .assertTextEquals(composeTestRule.activity.getString(R.string.fridge_name_required))
    composeTestRule.runOnIdle { assertTrue(repository.submissions.isEmpty()) }

    composeTestRule
        .onNodeWithTag(C.Tag.fridge_name_input)
        .performScrollTo()
        .performTextInput("Milk")
    composeTestRule
        .onNodeWithTag(C.Tag.fridge_category_input)
        .performScrollTo()
        .performTextInput("Dairy")
    composeTestRule
        .onNodeWithTag(C.Tag.fridge_expiration_input)
        .performScrollTo()
        .performTextInput("2026-02-30")
    Espresso.closeSoftKeyboard()
    composeTestRule.onNodeWithTag(C.Tag.fridge_save_item).assertIsEnabled().performClick()
    composeTestRule
        .onNodeWithTag(C.Tag.fridge_form_error)
        .performScrollTo()
        .assertIsDisplayed()
        .assertTextEquals(composeTestRule.activity.getString(R.string.fridge_date_invalid))
    composeTestRule.runOnIdle { assertTrue(repository.submissions.isEmpty()) }

    composeTestRule
        .onNodeWithTag(C.Tag.fridge_expiration_input)
        .performScrollTo()
        .performTextReplacement(expirationDate.toString())
    Espresso.closeSoftKeyboard()
    composeTestRule.onNodeWithTag(C.Tag.fridge_save_item).assertIsEnabled().performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      viewModel.uiState.value.items.size == 1 && !viewModel.uiState.value.isAddItemDialogOpen
    }

    composeTestRule.onNodeWithTag(C.Tag.fridge_add_dialog).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.fridge_empty).assertDoesNotExist()
    composeTestRule.onNodeWithTag("${C.Tag.fridge_item_prefix}test-item-1").assertIsDisplayed()
    composeTestRule.onNodeWithText("Milk").assertIsDisplayed()
    composeTestRule
        .onNodeWithText(composeTestRule.activity.getString(R.string.fridge_item_category, "Dairy"))
        .assertIsDisplayed()
    composeTestRule
        .onNodeWithText("Expires ${ExpirationDates.formatDisplay(storedExpirationDate)}")
        .assertIsDisplayed()
    composeTestRule.runOnIdle {
      val expected =
          Item(
              name = "Milk",
              ownerId = "test-user",
              householdId = "test-household",
              expirationDate = storedExpirationDate,
              category = "Dairy")
      assertEquals(listOf(expected), repository.submissions)
      assertEquals(listOf(expected.copy(id = "test-item-1")), viewModel.uiState.value.items)
    }
  }

  @Test
  fun enteredQuantityIsSavedWithTheNewItem() {
    val repository = TestItemRepository()
    val viewModel = showManualEntryScreen(repository)
    composeTestRule.onNodeWithTag(C.Tag.fridge_add_item).performClick()
    composeTestRule.onNodeWithTag(C.Tag.fridge_name_input).performTextInput("Milk")
    composeTestRule
        .onNodeWithTag(C.Tag.fridge_quantity_input)
        .performScrollTo()
        .performTextReplacement("12")
    Espresso.closeSoftKeyboard()
    composeTestRule.onNodeWithTag(C.Tag.fridge_save_item).performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      viewModel.uiState.value.items.size == 1 && !viewModel.uiState.value.isAddItemDialogOpen
    }
    composeTestRule.onNodeWithText("Milk").assertIsDisplayed()
    composeTestRule.runOnIdle {
      assertEquals(1, repository.submissions.size)
      assertEquals(12, repository.submissions.single().quantity)
      assertEquals(12, viewModel.uiState.value.items.single().quantity)
    }
  }

  @Test
  fun invalidQuantitiesShowValidationAndCanBeCorrected() {
    val repository = TestItemRepository()
    val viewModel = showManualEntryScreen(repository)
    composeTestRule.onNodeWithTag(C.Tag.fridge_add_item).performClick()
    composeTestRule.onNodeWithTag(C.Tag.fridge_name_input).performTextInput("Milk")
    listOf("", "abc", "1.5", "0", "-1", "2147483648").forEach { input ->
      composeTestRule
          .onNodeWithTag(C.Tag.fridge_quantity_input)
          .performScrollTo()
          .performTextReplacement(input)
      Espresso.closeSoftKeyboard()
      composeTestRule.onNodeWithTag(C.Tag.fridge_save_item).assertIsEnabled().performClick()
      composeTestRule
          .onNodeWithTag(C.Tag.fridge_form_error)
          .performScrollTo()
          .assertIsDisplayed()
          .assertTextEquals(composeTestRule.activity.getString(R.string.fridge_quantity_invalid))
      composeTestRule.runOnIdle {
        assertTrue(repository.submissions.isEmpty())
        assertTrue(viewModel.uiState.value.items.isEmpty())
        assertEquals(input, viewModel.uiState.value.itemQuantity)
      }
    }
    composeTestRule
        .onNodeWithTag(C.Tag.fridge_quantity_input)
        .performScrollTo()
        .performTextReplacement("4")
    Espresso.closeSoftKeyboard()
    composeTestRule.onNodeWithTag(C.Tag.fridge_save_item).performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      viewModel.uiState.value.items.size == 1 && !viewModel.uiState.value.isAddItemDialogOpen
    }
    composeTestRule.onNodeWithTag(C.Tag.fridge_add_dialog).assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertEquals(1, repository.submissions.size)
      assertEquals(4, repository.submissions.single().quantity)
    }
  }

  @Test
  fun cancelDoesNotSaveAnItem() {
    val repository = TestItemRepository()
    showManualEntryScreen(repository)
    composeTestRule.onNodeWithTag(C.Tag.fridge_add_item).performClick()
    composeTestRule.onNodeWithTag(C.Tag.fridge_name_input).performTextInput("Milk")
    composeTestRule.onNodeWithTag(C.Tag.fridge_quantity_input).performTextReplacement("4")
    Espresso.closeSoftKeyboard()
    composeTestRule.onNodeWithTag(C.Tag.fridge_cancel_item).assertIsEnabled().performClick()

    composeTestRule.onNodeWithTag(C.Tag.fridge_add_dialog).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.fridge_empty).assertIsDisplayed()
    composeTestRule.runOnIdle { assertTrue(repository.submissions.isEmpty()) }
    composeTestRule.onNodeWithTag(C.Tag.fridge_add_item).performClick()
    composeTestRule.onNodeWithTag(C.Tag.fridge_quantity_input).assertTextContains("1")
    composeTestRule.onNodeWithTag(C.Tag.fridge_cancel_item).performClick()
  }

  @Test
  fun failedSaveRetainsTheFormAndAllowsRetry() {
    val repository = TestItemRepository().apply { returnNullOnAdd = true }
    val viewModel = showManualEntryScreen(repository)
    composeTestRule.onNodeWithTag(C.Tag.fridge_add_item).performClick()
    composeTestRule.onNodeWithTag(C.Tag.fridge_name_input).performTextInput("Milk")
    composeTestRule.onNodeWithTag(C.Tag.fridge_quantity_input).performTextReplacement("3")
    Espresso.closeSoftKeyboard()
    composeTestRule.onNodeWithTag(C.Tag.fridge_save_item).performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      viewModel.uiState.value.formError == FridgeFormError.SAVE_FAILED
    }

    composeTestRule.onNodeWithTag(C.Tag.fridge_add_dialog).assertIsDisplayed()
    composeTestRule
        .onNodeWithTag(C.Tag.fridge_form_error)
        .performScrollTo()
        .assertIsDisplayed()
        .assertTextEquals(composeTestRule.activity.getString(R.string.fridge_save_failed))
    composeTestRule
        .onNodeWithTag(C.Tag.fridge_name_input)
        .performScrollTo()
        .assertTextContains("Milk")
    composeTestRule
        .onNodeWithTag(C.Tag.fridge_quantity_input)
        .performScrollTo()
        .assertTextContains("3")
    composeTestRule.runOnIdle {
      assertEquals(1, repository.submissions.size)
      assertTrue(viewModel.uiState.value.items.isEmpty())
      repository.returnNullOnAdd = false
    }

    composeTestRule.onNodeWithTag(C.Tag.fridge_save_item).assertIsEnabled().performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      viewModel.uiState.value.items.size == 1 && !viewModel.uiState.value.isAddItemDialogOpen
    }
    composeTestRule.onNodeWithTag(C.Tag.fridge_add_dialog).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.fridge_form_error).assertDoesNotExist()
    composeTestRule.onNodeWithText("Milk").assertIsDisplayed()
    composeTestRule.runOnIdle {
      assertEquals(2, repository.submissions.size)
      assertEquals("Milk", viewModel.uiState.value.items.single().name)
      assertEquals(listOf(3, 3), repository.submissions.map { it.quantity })
      assertEquals(3, viewModel.uiState.value.items.single().quantity)
    }
  }

  @Test
  fun savingDisablesTheFormUntilTheRepositoryCompletes() {
    val gate = CompletableDeferred<Unit>()
    val repository = TestItemRepository().apply { addGate = gate }
    val viewModel = showManualEntryScreen(repository)
    composeTestRule.onNodeWithTag(C.Tag.fridge_add_item).performClick()
    composeTestRule.onNodeWithTag(C.Tag.fridge_name_input).performTextInput("Milk")
    composeTestRule.onNodeWithTag(C.Tag.fridge_quantity_input).performTextReplacement("6")
    Espresso.closeSoftKeyboard()
    composeTestRule.onNodeWithTag(C.Tag.fridge_save_item).performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.isSaving }

    listOf(
            C.Tag.fridge_save_item,
            C.Tag.fridge_cancel_item,
            C.Tag.fridge_name_input,
            C.Tag.fridge_quantity_input,
            C.Tag.fridge_category_input,
            C.Tag.fridge_expiration_input)
        .forEach { testTag -> composeTestRule.onNodeWithTag(testTag).assertIsNotEnabled() }
    composeTestRule.runOnIdle {
      assertEquals(1, repository.submissions.size)
      gate.complete(Unit)
    }

    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      viewModel.uiState.value.items.size == 1 && !viewModel.uiState.value.isAddItemDialogOpen
    }
    composeTestRule.onNodeWithTag(C.Tag.fridge_add_dialog).assertDoesNotExist()
    composeTestRule.onNodeWithText("Milk").assertIsDisplayed()
    composeTestRule.runOnIdle {
      assertEquals(1, repository.submissions.size)
      assertEquals(6, repository.submissions.single().quantity)
      assertEquals(6, viewModel.uiState.value.items.single().quantity)
    }
  }

  private fun showManualEntryScreen(repository: TestItemRepository): FridgeViewModel {
    val imageRepository =
        object : FoodImageRepository {
          override suspend fun searchByTitle(title: String) = FoodImageSearchResult.NotFound
        }
    val viewModel =
        FridgeViewModel(
            repository,
            "test-user",
            "test-household",
            FoodImageSelector(imageRepository),
            BarcodeScanner { BarcodeScanResult.NotFound })
    composeTestRule.runOnUiThread {
      composeTestRule.activity.viewModelStore.put("manual-item-test", viewModel)
    }
    composeTestRule.setContent {
      SampleAppTheme(dynamicColor = false) { FridgeScreen(viewModel = viewModel) }
    }
    return viewModel
  }

  private class TestItemRepository : ItemRepository {
    private val items = MutableStateFlow<List<Item>>(emptyList())
    val submissions = mutableListOf<Item>()
    var returnNullOnAdd = false
    var addGate: CompletableDeferred<Unit>? = null

    override fun getPrivateItems(userId: String): Flow<List<Item>> =
        items.map { current -> current.filter { it.ownerId == userId && it.householdId == null } }

    override fun getSharedItems(householdId: String): Flow<List<Item>> =
        items.map { current -> current.filter { it.householdId == householdId } }

    override suspend fun updateItemImageIfAbsent(
        itemId: String,
        expectedName: String,
        image: ItemImage
    ): ItemImageUpdateResult = error("Not used in this test")

    override fun updateItem(itemId: String, edit: ItemEdit): ItemWrite =
        error("Not used in this test")

    override fun setItemSharing(
        itemId: String,
        sharing: com.android.sharemate.model.item.ItemSharing,
    ): ItemWrite = error("Sharing updates are not used in this test")

    override fun setItemStatus(itemId: String, status: ItemStatus): ItemWrite =
        error("Not used in this test")

    override fun deleteItemQueued(itemId: String): ItemWrite = error("Not used in this test")

    override suspend fun addItem(item: Item): String? {
      submissions += item
      addGate?.await()
      if (returnNullOnAdd) return null
      val itemId = "test-item-${submissions.size}"
      items.value += item.copy(id = itemId)
      return itemId
    }

    override suspend fun deleteItem(itemId: String): Boolean {
      val existed = items.value.any { it.id == itemId }
      items.value = items.value.filterNot { it.id == itemId }
      return existed
    }
  }
}
