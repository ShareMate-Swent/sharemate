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
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.resources.C
import com.android.sharemate.ui.theme.SampleAppTheme
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
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
    val storedExpirationDate = Date.from(expirationDate.atStartOfDay(ZoneOffset.UTC).toInstant())

    composeTestRule
        .onNodeWithTag(C.Tag.fridge_add_item)
        .assertIsDisplayed()
        .assertIsEnabled()
        .performClick()
    composeTestRule.onNodeWithTag(C.Tag.fridge_add_dialog).assertIsDisplayed()
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
        .onNodeWithText(
            "Expires ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(storedExpirationDate)}")
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
  fun cancelDoesNotSaveAnItem() {
    val repository = TestItemRepository()
    showManualEntryScreen(repository)
    composeTestRule.onNodeWithTag(C.Tag.fridge_add_item).performClick()
    composeTestRule.onNodeWithTag(C.Tag.fridge_name_input).performTextInput("Milk")
    Espresso.closeSoftKeyboard()
    composeTestRule.onNodeWithTag(C.Tag.fridge_cancel_item).assertIsEnabled().performClick()

    composeTestRule.onNodeWithTag(C.Tag.fridge_add_dialog).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.fridge_empty).assertIsDisplayed()
    composeTestRule.runOnIdle { assertTrue(repository.submissions.isEmpty()) }
  }

  @Test
  fun failedSaveRetainsTheFormAndAllowsRetry() {
    val repository = TestItemRepository().apply { returnNullOnAdd = true }
    val viewModel = showManualEntryScreen(repository)
    composeTestRule.onNodeWithTag(C.Tag.fridge_add_item).performClick()
    composeTestRule.onNodeWithTag(C.Tag.fridge_name_input).performTextInput("Milk")
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
    }
  }

  @Test
  fun savingDisablesTheFormUntilTheRepositoryCompletes() {
    val gate = CompletableDeferred<Unit>()
    val repository = TestItemRepository().apply { addGate = gate }
    val viewModel = showManualEntryScreen(repository)
    composeTestRule.onNodeWithTag(C.Tag.fridge_add_item).performClick()
    composeTestRule.onNodeWithTag(C.Tag.fridge_name_input).performTextInput("Milk")
    Espresso.closeSoftKeyboard()
    composeTestRule.onNodeWithTag(C.Tag.fridge_save_item).performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.isSaving }

    listOf(
            C.Tag.fridge_save_item,
            C.Tag.fridge_cancel_item,
            C.Tag.fridge_name_input,
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
    composeTestRule.runOnIdle { assertEquals(1, repository.submissions.size) }
  }

  private fun showManualEntryScreen(repository: TestItemRepository): FridgeViewModel {
    val viewModel = FridgeViewModel(repository, "test-user", "test-household")
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
        items.map { current -> current.filter { it.ownerId == userId && !it.isShared } }

    override fun getSharedItems(householdId: String): Flow<List<Item>> =
        items.map { current -> current.filter { it.householdId == householdId } }

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
