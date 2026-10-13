// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemEdit
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.item.ItemStatus
import com.android.sharemate.model.item.ItemWrite
import com.android.sharemate.resources.C
import com.android.sharemate.ui.theme.SampleAppTheme
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

/** Exercises the real ViewModel and dynamic controls without Firebase or network access. */
@RunWith(AndroidJUnit4::class)
class FridgeScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private lateinit var repository: ScreenItemRepository
  private lateinit var viewModel: FridgeViewModel

  @Test
  fun queuedStatusActionsRetainRecordsAndRejectionRestoresAnEditableCard() {
    showScreen()
    compose.onNodeWithTag(C.Tag.fridge_eaten_prefix + "carrot").performScrollTo().performClick()
    item("carrot").assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.fridge_discard_prefix + "banana").performScrollTo().performClick()
    item("banana").assertDoesNotExist()
    compose.runOnIdle {
      assertEquals(
          listOf("carrot" to ItemStatus.EATEN, "banana" to ItemStatus.DISCARDED),
          repository.statusChanges)
      assertEquals(3, viewModel.uiState.value.items.size)
      assertTrue(viewModel.uiState.value.pendingStatusIds.isEmpty())
      assertTrue(repository.confirmations.none { it.isCompleted })
      repository.inventory.value =
          repository.inventory.value.map {
            if (it.id == "carrot") it.copy(status = ItemStatus.ACTIVE) else it
          }
      repository.confirmations.first().complete(Result.failure(IllegalStateException("Denied")))
    }
    item("carrot").performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.fridge_status_error).assertIsDisplayed()
    compose.runOnIdle {
      assertEquals(
          ItemStatus.DISCARDED, viewModel.uiState.value.items.first { it.id == "banana" }.status)
    }
    compose.onNodeWithTag(C.Tag.fridge_edit_prefix + "carrot").performScrollTo().performClick()
    compose.onNodeWithTag(C.Tag.fridge_add_dialog).assertIsDisplayed()
  }

  @Test
  fun sortChipsChangeTheRenderedOrder() {
    showScreen()
    compose.onNodeWithText("Expiry date").assertIsSelected()
    assertItemOrder("carrot", "banana", "apple")

    compose.onNodeWithText("Name").performClick().assertIsSelected()
    compose.onNodeWithText("Expiry date").assertIsNotSelected()
    assertItemOrder("apple", "banana", "carrot")

    compose.onNodeWithText("Expiry date").performClick().assertIsSelected()
    assertItemOrder("carrot", "banana", "apple")
  }

  @Test
  fun categoryDropdownFiltersItemsAndCanBeCleared() {
    showScreen()
    filterChip("Category").performScrollTo().performClick()
    compose.onNodeWithTag("fridge_filter_category_Fruits").assertIsDisplayed().performClick()
    filterChip("Category: Fruits").assertIsSelected()
    item("banana").assertIsDisplayed()
    item("apple").assertIsDisplayed()
    item("carrot").assertDoesNotExist()

    filterChip("Category: Fruits").performScrollTo().performClick()
    compose.onNodeWithTag("fridge_filter_category_all").performClick()
    filterChip("Category").assertIsNotSelected()
    assertItemOrder("carrot", "banana", "apple")
  }

  @Test
  fun ownerAndCategoryFiltersCombineAndShowAnEmptyResult() {
    showScreen()
    filterChip("Owner").performScrollTo().performClick()
    compose.onNodeWithTag("fridge_filter_owner_alice").performClick()
    item("banana").assertIsDisplayed()
    item("carrot").assertDoesNotExist()
    item("apple").assertDoesNotExist()

    filterChip("Category").performScrollTo().performClick()
    compose.onNodeWithTag("fridge_filter_category_Vegetables").performClick()
    compose
        .onNodeWithTag(C.Tag.fridge_empty)
        .assertIsDisplayed()
        .assertTextEquals("No items match the selected filters.")
    item("banana").assertDoesNotExist()

    filterChip("Owner: alice").performScrollTo().performClick()
    compose.onNodeWithTag("fridge_filter_owner_all").performClick()
    item("carrot").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.fridge_empty).assertDoesNotExist()

    filterChip("Category: Vegetables").performScrollTo().performClick()
    compose.onNodeWithTag("fridge_filter_category_all").performClick()
    assertItemOrder("carrot", "banana", "apple")
  }

  @Test
  fun emptyInventoryDisablesDropdownsButAllowsAddingItems() {
    showScreen(emptyList())
    compose
        .onNodeWithTag(C.Tag.fridge_empty)
        .assertIsDisplayed()
        .assertTextEquals("Your fridge is empty")
    filterChip("Category").performScrollTo().assertIsNotEnabled()
    filterChip("Owner").performScrollTo().assertIsNotEnabled()
    compose.onNodeWithText("Name").performScrollTo().performClick().assertIsSelected()
    compose.onNodeWithTag(C.Tag.fridge_add_item).assertIsEnabled().performClick()
    compose.onNodeWithTag(C.Tag.fridge_add_dialog).assertIsDisplayed()
  }

  private fun showScreen(items: List<Item> = inventory) {
    compose.runOnUiThread {
      repository = ScreenItemRepository(items)
      viewModel = FridgeViewModel(repository, "alice", "household")
      compose.activity.viewModelStore.put("fridge-screen-test", viewModel)
    }
    compose.setContent {
      SampleAppTheme(dynamicColor = false) { FridgeScreen(viewModel = viewModel) }
    }
    compose.waitUntil(5_000) {
      !viewModel.uiState.value.isLoading && viewModel.visibleItems.value.size == items.size
    }
  }

  private fun item(id: String) = compose.onNodeWithTag("${C.Tag.fridge_item_prefix}$id")

  // Item category labels share the chip text but have no selection semantics.
  private fun filterChip(label: String) = compose.onNode(hasText(label) and isSelectable())

  private fun assertItemOrder(vararg ids: String) {
    val list = compose.onNodeWithTag(C.Tag.fridge_item_list)
    ids.forEachIndexed { index, id ->
      assertEquals(index, list.fetchSemanticsNode().config[SemanticsProperties.IndexForKey](id))
      list.performScrollToIndex(index)
      item(id).assertIsDisplayed()
    }
  }

  private class ScreenItemRepository(items: List<Item>) : ItemRepository {
    val inventory = MutableStateFlow(items)
    val statusChanges = mutableListOf<Pair<String, ItemStatus>>()
    val confirmations = mutableListOf<CompletableDeferred<Result<Unit>>>()

    override fun getSharedItems(householdId: String): Flow<List<Item>> =
        inventory.map { items -> items.filter { it.householdId == householdId } }

    override fun getPrivateItems(userId: String): Flow<List<Item>> =
        inventory.map { items -> items.filter { it.ownerId == userId && !it.isShared } }

    override fun updateItem(itemId: String, edit: ItemEdit): ItemWrite =
        error("Not used in this test")

    override fun setItemStatus(itemId: String, status: ItemStatus): ItemWrite {
      statusChanges += itemId to status
      inventory.value =
          inventory.value.map { if (it.id == itemId) it.copy(status = status) else it }
      val confirmation = CompletableDeferred<Result<Unit>>()
      confirmations += confirmation
      return ItemWrite.Queued(confirmation)
    }

    override fun deleteItemQueued(itemId: String): ItemWrite = error("Not used in this test")

    override suspend fun addItem(item: Item): String? = error("Read-only test repository")

    override suspend fun deleteItem(itemId: String): Boolean = error("Read-only test repository")
  }

  private val inventory =
      listOf(
          Item(
              id = "banana",
              name = "Banana",
              ownerId = "alice",
              householdId = "household",
              category = "Fruits",
              expirationDate = Date(2_000)),
          Item(
              id = "carrot",
              name = "Carrot",
              ownerId = "bob",
              householdId = "household",
              category = "Vegetables",
              expirationDate = Date(1_000)),
          Item(
              id = "apple",
              name = "Apple",
              ownerId = "bob",
              householdId = "household",
              category = "Fruits"),
      )
}
