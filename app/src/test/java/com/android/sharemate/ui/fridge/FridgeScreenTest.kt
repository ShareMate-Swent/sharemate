// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.fridge

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.resources.C
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FridgeScreenTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun defaultScreenShowsInventoryAndDisabledCategoryControls() {
    composeRule.setContent { MaterialTheme { FridgeScreen() } }

    composeRule.onNodeWithTag(C.Tag.fridge_title).assertIsDisplayed()
    composeRule.onNodeWithTag(C.Tag.fridge_empty).assertIsDisplayed()
    composeRule.onNodeWithText("All").assertIsDisplayed()
    composeRule.onNodeWithText("Fruits").assertIsDisplayed()
    composeRule.onNodeWithText("Sort").assertIsDisplayed()
    composeRule.onNodeWithText("Yours").assertIsDisplayed()
    composeRule.onNodeWithText("Shared").assertIsDisplayed()
  }

  @Test
  fun sortAndFilterControlsUpdateViewModel() {
    val repository =
        FakeItemRepository(
            listOf(
                Item(id = "milk", name = "Milk", category = "Dairy", ownerId = "owner-1"),
                Item(id = "bread", name = "Bread", category = "Bakery", ownerId = "owner-2")))
    val viewModel = FridgeViewModel(repository, USER_ID, HOUSEHOLD_ID)

    composeRule.setContent { MaterialTheme { FridgeScreen(viewModel) } }
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Name").performClick()
    assertEquals(FridgeSortOrder.NAME, viewModel.sortOrder.value)

    composeRule.onNodeWithText("Category").performClick()
    composeRule.onNodeWithTag("fridge_filter_category_Dairy").performClick()
    assertEquals("Dairy", viewModel.selectedCategory.value)

    composeRule.onNodeWithText("Owner").performClick()
    composeRule.onNodeWithTag("fridge_filter_owner_owner-1").performClick()
    assertEquals("owner-1", viewModel.selectedOwnerId.value)
  }

  @Test
  fun categoryChipRemainsUniqueWhenItemLabelsMatch() {
    val repository =
        FakeItemRepository(
            listOf(
                Item(id = "apple", name = "Apple", category = "Fruits"),
                Item(id = "banana", name = "Banana", category = "Fruits"),
                Item(id = "carrot", name = "Carrot", category = "Vegetables")))
    val viewModel = FridgeViewModel(repository, USER_ID, HOUSEHOLD_ID)
    composeRule.setContent { MaterialTheme { FridgeScreen(viewModel) } }

    for ((category, matchingNodes) in listOf("Fruits" to 3, "Vegetables" to 2)) {
      composeRule.onNode(hasText("Category") and isSelectable()).performClick()
      composeRule.onNodeWithTag("fridge_filter_category_$category").performClick()
      composeRule.onAllNodesWithText("Category: $category").assertCountEquals(matchingNodes)
      composeRule
          .onNode(hasText("Category: $category") and isSelectable())
          .assertIsSelected()
          .performClick()
      composeRule.onNodeWithTag("fridge_filter_category_all").performClick()
      composeRule.onNode(hasText("Category") and isSelectable()).assertIsNotSelected()
      assertEquals(null, viewModel.selectedCategory.value)
    }
  }

  @Test
  fun highlightsExpiredAndSoonToExpireItems() {
    composeRule.setContent {
      MaterialTheme {
        FridgeItem(item = itemExpiringIn(-1))
        FridgeItem(item = itemExpiringIn(3).copy(id = "soon"))
      }
    }

    composeRule.onNodeWithText("Expired on", substring = true).assertIsDisplayed()
    composeRule.onNodeWithText("Expires soon:", substring = true).assertIsDisplayed()
  }

  @Test
  fun doesNotMarkItemsExpiringAfterThreeDaysAsSoon() {
    composeRule.setContent { MaterialTheme { FridgeItem(item = itemExpiringIn(4)) } }

    composeRule.onNodeWithText("Expires ", substring = true).assertIsDisplayed()
    composeRule.onAllNodesWithText("Expires soon:", substring = true).assertCountEquals(0)
  }

  private fun itemExpiringIn(days: Long) =
      Item(
          id = "milk",
          name = "Milk",
          expirationDate =
              Date.from(
                  LocalDate.now().plusDays(days).atStartOfDay(ZoneId.systemDefault()).toInstant()))

  private class FakeItemRepository(items: List<Item>) : ItemRepository {
    private val sharedItems = MutableStateFlow(items)

    override fun getPrivateItems(userId: String): Flow<List<Item>> = MutableStateFlow(emptyList())

    override fun getSharedItems(householdId: String): Flow<List<Item>> = sharedItems

    override suspend fun addItem(item: Item): String? = error("Not used in this test.")

    override suspend fun deleteItem(itemId: String): Boolean = error("Not used in this test.")
  }

  private companion object {
    const val USER_ID = "user-1"
    const val HOUSEHOLD_ID = "household-1"
  }
}
