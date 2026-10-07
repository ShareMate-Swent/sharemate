// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.fridge

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemRepository
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
  fun sortAndFilterControlsUpdateViewModel() {
    val milk = Item(id = "milk", name = "Milk", category = "Dairy", ownerId = "owner-1")
    val repository = FakeItemRepository(listOf(milk))
    val viewModel = FridgeViewModel(repository, "household")

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
  fun highlightsItemsExpiringWithinThreeDays() {
    composeRule.setContent { MaterialTheme { FridgeItem(item = itemExpiringIn(3)) } }

    composeRule.onNodeWithText("Expires soon:", substring = true).assertIsDisplayed()
  }

  @Test
  fun highlightsAlreadyExpiredItems() {
    composeRule.setContent { MaterialTheme { FridgeItem(item = itemExpiringIn(-1)) } }

    composeRule.onNodeWithText("Expired on", substring = true).assertIsDisplayed()
  }

  @Test
  fun doesNotHighlightItemsExpiringAfterThreeDays() {
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
                  LocalDate.now().plusDays(days).atStartOfDay(ZoneId.systemDefault()).toInstant()),
      )

  private class FakeItemRepository(items: List<Item>) : ItemRepository {
    private val items = MutableStateFlow(items)

    override fun getPrivateItems(userId: String): Flow<List<Item>> = MutableStateFlow(emptyList())

    override fun getSharedItems(householdId: String): Flow<List<Item>> = items

    override suspend fun addItem(item: Item): String = error("Not used in this test.")

    override suspend fun deleteItem(itemId: String): Boolean = error("Not used in this test.")
  }
}
