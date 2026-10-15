// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.R
import com.android.sharemate.model.image.FoodImageRepository
import com.android.sharemate.model.image.FoodImageSearchResult
import com.android.sharemate.model.image.FoodImageSelector
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemEdit
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.item.ItemStatus
import com.android.sharemate.model.item.ItemWrite
import com.android.sharemate.resources.C
import com.android.sharemate.ui.theme.SampleAppTheme
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
class ItemRemovalTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private val milk =
      Item(id = "milk", name = "Milk", ownerId = "test-user", householdId = "test-household")
  private val bread =
      Item(id = "bread", name = "Bread", ownerId = "test-user", householdId = "test-household")

  @Test
  fun cancelClosesConfirmationWithoutDeleting() {
    val repository = TestItemRepository(listOf(milk))
    showFridge(repository)
    requestRemoval(milk)
    composeTestRule
        .onNodeWithText(
            composeTestRule.activity.getString(R.string.fridge_remove_confirmation, milk.name))
        .assertIsDisplayed()
    composeTestRule.runOnIdle { assertTrue(repository.deletedIds.isEmpty()) }
    composeTestRule.onNodeWithTag(C.Tag.fridge_cancel_remove).assertIsEnabled().performClick()

    composeTestRule.onNodeWithTag(C.Tag.fridge_remove_dialog).assertDoesNotExist()
    composeTestRule.onNodeWithText(milk.name).assertIsDisplayed()
    composeTestRule.runOnIdle { assertTrue(repository.deletedIds.isEmpty()) }
  }

  @Test
  fun confirmationRemovesOnlyTheSelectedItem() {
    val repository = TestItemRepository(listOf(milk, bread))
    val viewModel = showFridge(repository)
    requestRemoval(milk)
    composeTestRule.onNodeWithTag(C.Tag.fridge_confirm_remove).assertIsEnabled().performClick()
    waitForRemoval(viewModel, milk.id)

    composeTestRule.onNodeWithTag(C.Tag.fridge_remove_dialog).assertDoesNotExist()
    composeTestRule.onNodeWithTag("${C.Tag.fridge_item_prefix}${milk.id}").assertDoesNotExist()
    composeTestRule.onNodeWithText(bread.name).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.fridge_empty).assertDoesNotExist()
    composeTestRule.runOnIdle { assertEquals(listOf(milk.id), repository.deletedIds) }
  }

  @Test
  fun removingTheLastItemShowsTheExistingEmptyState() {
    val repository = TestItemRepository(listOf(milk))
    val viewModel = showFridge(repository)
    requestRemoval(milk)
    composeTestRule.onNodeWithTag(C.Tag.fridge_confirm_remove).performClick()
    waitForRemoval(viewModel, milk.id)

    composeTestRule.onNodeWithTag(C.Tag.fridge_remove_dialog).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.fridge_item_list).assertDoesNotExist()
    composeTestRule
        .onNodeWithTag(C.Tag.fridge_empty)
        .assertIsDisplayed()
        .assertTextEquals("Your fridge is empty")
    composeTestRule.runOnIdle { assertEquals(listOf(milk.id), repository.deletedIds) }
  }

  @Test
  fun failedDeletionRetainsTheItemAndAllowsRetry() {
    val repository = TestItemRepository(listOf(milk)).apply { returnFalseOnDelete = true }
    val viewModel = showFridge(repository)
    requestRemoval(milk)
    composeTestRule.onNodeWithTag(C.Tag.fridge_confirm_remove).performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.removalFailed }

    composeTestRule.onNodeWithTag(C.Tag.fridge_remove_dialog).assertIsDisplayed()
    composeTestRule
        .onNodeWithTag(C.Tag.fridge_removal_error)
        .assertIsDisplayed()
        .assertTextEquals(composeTestRule.activity.getString(R.string.fridge_remove_failed))
    composeTestRule.runOnIdle {
      assertEquals(listOf(milk), viewModel.uiState.value.items)
      assertEquals(listOf(milk.id), repository.deletedIds)
      repository.returnFalseOnDelete = false
    }
    composeTestRule.onNodeWithTag(C.Tag.fridge_confirm_remove).assertIsEnabled().performClick()
    waitForRemoval(viewModel, milk.id)
    composeTestRule.onNodeWithTag(C.Tag.fridge_empty).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.fridge_removal_error).assertDoesNotExist()
    composeTestRule.runOnIdle { assertEquals(listOf(milk.id, milk.id), repository.deletedIds) }
  }

  @Test
  fun offlineDeletionClosesConfirmationWithoutAwaitingServer() {
    val gate = CompletableDeferred<Result<Unit>>()
    val repository =
        TestItemRepository(listOf(milk, bread)).apply {
          deleteConfirmation = gate
          emitOnDelete = false
        }
    val viewModel = showFridge(repository)
    requestRemoval(milk)
    composeTestRule.onNodeWithTag(C.Tag.fridge_confirm_remove).performClick()
    composeTestRule.waitUntil(5_000) { !viewModel.uiState.value.isDeleting }
    for (item in listOf(milk, bread)) {
      composeTestRule
          .onNodeWithTag("fridge_item_actions_${item.id}")
          .performScrollTo()
          .performClick()
      listOf(
              C.Tag.fridge_edit_prefix,
              C.Tag.fridge_remove_prefix,
              C.Tag.fridge_eaten_prefix,
              C.Tag.fridge_discard_prefix)
          .forEach {
            val action = composeTestRule.onNodeWithTag(it + item.id)
            if (item.id == milk.id) action.assertIsNotEnabled() else action.assertIsEnabled()
          }
      Espresso.pressBack()
    }
    requestRemoval(bread)
    composeTestRule.onNodeWithTag(C.Tag.fridge_cancel_remove).performClick()
    composeTestRule.runOnIdle { repository.items.value = emptyList() }
    waitForRemoval(viewModel, milk.id)
    composeTestRule.runOnIdle {
      viewModel.confirmRemoval()
      viewModel.cancelRemoval()
      assertEquals(listOf(milk.id), repository.deletedIds)
      assertTrue(viewModel.uiState.value.items.isEmpty())
      assertTrue(!gate.isCompleted)
      gate.complete(Result.success(Unit))
    }
    waitForRemoval(viewModel, milk.id)
    composeTestRule.onNodeWithTag(C.Tag.fridge_remove_dialog).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.fridge_empty).assertIsDisplayed()
    composeTestRule.runOnIdle { assertEquals(listOf(milk.id), repository.deletedIds) }
  }

  private fun requestRemoval(item: Item) {
    composeTestRule.onNodeWithTag("fridge_item_actions_${item.id}").performClick()
    composeTestRule
        .onNodeWithTag("${C.Tag.fridge_remove_prefix}${item.id}")
        .assertIsDisplayed()
        .assertIsEnabled()
        .performClick()
    composeTestRule.onNodeWithTag(C.Tag.fridge_remove_dialog).assertIsDisplayed()
  }

  private fun waitForRemoval(viewModel: FridgeViewModel, itemId: String) {
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      viewModel.uiState.value.items.none { it.id == itemId } &&
          viewModel.uiState.value.pendingRemovalItem == null &&
          !viewModel.uiState.value.isDeleting
    }
  }

  private fun showFridge(repository: TestItemRepository): FridgeViewModel {
    val imageRepository =
        object : FoodImageRepository {
          override suspend fun searchByTitle(title: String) = FoodImageSearchResult.NotFound
        }
    val viewModel =
        FridgeViewModel(
            repository, "test-user", "test-household", FoodImageSelector(imageRepository))
    composeTestRule.runOnUiThread {
      composeTestRule.activity.viewModelStore.put("removal-test", viewModel)
    }
    composeTestRule.setContent {
      val state by viewModel.uiState.collectAsState()
      SampleAppTheme(dynamicColor = false) {
        FridgeScreen(
            uiState = state,
            onEditItem = viewModel::openEditItemDialog,
            onStatusChange = viewModel::setItemStatus,
            onRemoveItem = viewModel::requestRemoval,
            onCancelRemoval = viewModel::cancelRemoval,
            onConfirmRemoval = viewModel::confirmRemoval)
      }
    }
    composeTestRule.waitUntil(timeoutMillis = 5_000) { !viewModel.uiState.value.isLoading }
    return viewModel
  }

  private class TestItemRepository(initialItems: List<Item>) : ItemRepository {
    val items = MutableStateFlow(initialItems)
    val deletedIds = mutableListOf<String>()
    var emitOnDelete = true

    var returnFalseOnDelete = false
    var deleteConfirmation: CompletableDeferred<Result<Unit>>? = null

    override fun getSharedItems(householdId: String): Flow<List<Item>> =
        items.map { current -> current.filter { it.householdId == householdId } }

    override fun getPrivateItems(userId: String): Flow<List<Item>> =
        items.map { current -> current.filter { it.ownerId == userId && !it.isShared } }

    override fun updateItem(itemId: String, edit: ItemEdit): ItemWrite =
        error("Not used in this test")

    override fun setItemStatus(itemId: String, status: ItemStatus): ItemWrite =
        error("Not used in this test")

    override fun deleteItemQueued(itemId: String): ItemWrite {
      deletedIds += itemId
      if (returnFalseOnDelete) return ItemWrite.Rejected(IllegalStateException("Delete failed"))
      if (emitOnDelete) items.value = items.value.filterNot { it.id == itemId }
      return ItemWrite.Queued(deleteConfirmation ?: CompletableDeferred(Result.success(Unit)))
    }

    override suspend fun addItem(item: Item): String? =
        throw UnsupportedOperationException("Item creation is not used by removal tests")

    override suspend fun deleteItem(itemId: String): Boolean = error("Must use queued deletion")
  }
}
