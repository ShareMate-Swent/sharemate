// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.fridge

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
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
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FridgeScreenTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun statusActionsAreAccessibleAndForwardTheCorrectLifecycleValue() {
    val changes = mutableListOf<ItemStatus>()
    composeRule.setContent {
      MaterialTheme {
        FridgeItem(
            item = Item(id = "milk", name = "Milk"),
            statusEnabled = true,
            onStatusChange = { changes += it })
      }
    }
    composeRule.onNodeWithContentDescription("Mark Milk as Eaten").assertIsEnabled().performClick()
    composeRule
        .onNodeWithContentDescription("Mark Milk as Thrown away")
        .assertIsEnabled()
        .performClick()
    assertEquals(listOf(ItemStatus.EATEN, ItemStatus.DISCARDED), changes)
  }

  @Test
  fun activeScreenHidesHistoryAndDisablesActionsDuringLocalSubmission() {
    val item = Item(id = "milk", name = "Milk")
    composeRule.setContent {
      MaterialTheme {
        FridgeScreen(
            uiState =
                FridgeUiState(
                    items =
                        listOf(
                            item,
                            item.copy(id = "eaten", status = ItemStatus.EATEN),
                            item.copy(id = "discarded", status = ItemStatus.DISCARDED)),
                    pendingStatusIds = setOf(item.id)),
            onStatusChange = { _, _ -> },
            onEditItem = {})
      }
    }
    composeRule.onNodeWithTag(C.Tag.fridge_eaten_prefix + item.id).assertIsNotEnabled()
    composeRule.onNodeWithTag(C.Tag.fridge_discard_prefix + item.id).assertIsNotEnabled()
    composeRule.onNodeWithTag(C.Tag.fridge_edit_prefix + item.id).assertIsNotEnabled()
    listOf("eaten", "discarded").forEach {
      composeRule.onNodeWithTag(C.Tag.fridge_item_prefix + it).assertDoesNotExist()
    }
  }

  @Test
  @OptIn(ExperimentalCoroutinesApi::class)
  fun deleteConfirmationCancelsAndQueuesCorrectItemOfflineWithoutAffectingOtherActions() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    val repository = com.android.sharemate.model.item.FakeItemRepository()
    val milk = Item(id = "milk", name = "Milk", householdId = HOUSEHOLD_ID)
    val bread = milk.copy(id = "bread", name = "Bread")
    repository.emitItems(listOf(milk, bread))
    val confirmation = CompletableDeferred<Result<Unit>>()
    repository.deleteConfirmation = confirmation
    val imageRepository =
        object : FoodImageRepository {
          override suspend fun searchByTitle(title: String) = FoodImageSearchResult.NotFound
        }
    val viewModel =
        FridgeViewModel(
            repository,
            USER_ID,
            HOUSEHOLD_ID,
            FoodImageSelector(imageRepository),
            BarcodeScanner { BarcodeScanResult.NotFound })
    val store = ViewModelStore().also { it.put("fridge", viewModel) }
    try {
      composeRule.setContent { MaterialTheme { FridgeScreen(viewModel) } }
      composeRule.onNodeWithContentDescription("Delete Milk").performClick()
      composeRule.onNodeWithTag(C.Tag.fridge_remove_dialog).assertIsDisplayed()
      val prompt = "Permanently delete Milk from the fridge? This cannot be undone."
      composeRule.onNodeWithText(prompt).assertIsDisplayed()
      composeRule.onNodeWithTag(C.Tag.fridge_cancel_remove).performClick()
      composeRule.onNodeWithTag(C.Tag.fridge_remove_dialog).assertDoesNotExist()
      composeRule.runOnIdle { assertEquals(emptyList<String>(), repository.deletedItemIds) }
      composeRule.onNodeWithContentDescription("Delete Milk").performClick()
      composeRule.onNodeWithTag(C.Tag.fridge_confirm_remove).performClick()
      composeRule.onNodeWithTag(C.Tag.fridge_remove_dialog).assertDoesNotExist()
      composeRule.onNodeWithTag(C.Tag.fridge_item_prefix + milk.id).assertDoesNotExist()
      composeRule.onNodeWithText("Bread").assertIsDisplayed()
      assertEquals(listOf(milk.id), repository.queuedDeletionIds)
      assertEquals(false, confirmation.isCompleted)
      composeRule.onNodeWithTag(C.Tag.fridge_edit_prefix + bread.id).performClick()
      assertEquals(bread.id, viewModel.uiState.value.editingItemId)
      composeRule.runOnUiThread { viewModel.dismissAddItemDialog() }
      composeRule.onNodeWithTag(C.Tag.fridge_eaten_prefix + bread.id).performClick()
      composeRule.onNodeWithTag(C.Tag.fridge_item_prefix + bread.id).assertDoesNotExist()
      assertEquals(listOf(bread.id to ItemStatus.EATEN), repository.statusChanges)
    } finally {
      store.clear()
      Dispatchers.resetMain()
    }
  }

  @Test
  fun removalSyncErrorRetainsIdentityAndStartsEightSecondTimeoutAfterDialogCloses() {
    val error = FridgeRemovalSyncError("Milk")
    val state =
        mutableStateOf(
            FridgeUiState(
                removalSyncError = error, pendingRemovalItem = Item(id = "bread", name = "Bread")))
    val dismissed = mutableListOf<FridgeRemovalSyncError>()
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
      MaterialTheme {
        FridgeScreen(uiState = state.value, onDismissRemovalSyncError = { dismissed += it })
      }
    }
    composeRule.mainClock.advanceTimeBy(9_000)
    composeRule.runOnIdle {
      assertEquals(emptyList<FridgeRemovalSyncError>(), dismissed)
      state.value = state.value.copy(pendingRemovalItem = null)
    }
    composeRule.mainClock.advanceTimeBy(100)
    composeRule.waitForIdle()
    composeRule.onNodeWithTag(C.Tag.fridge_removal_sync_error).assertIsDisplayed()
    val message = "Deletion of Milk could not sync. The item may reappear."
    composeRule.onNodeWithText(message).assertIsDisplayed()
    composeRule.mainClock.advanceTimeBy(7_800)
    composeRule.runOnIdle { assertEquals(emptyList<FridgeRemovalSyncError>(), dismissed) }
    composeRule.mainClock.advanceTimeBy(200)
    composeRule.runOnIdle { assertEquals(listOf(error), dismissed) }
  }

  @Test
  fun eachItemExposesAnAccessibleRemoveActionForItsOwnId() {
    val requests = mutableListOf<String>()
    val items = listOf(Item(id = "milk", name = "Milk"), Item(id = "bread", name = "Bread"))
    composeRule.setContent {
      MaterialTheme {
        FridgeScreen(uiState = FridgeUiState(items = items), onRemoveItem = { requests += it })
      }
    }

    composeRule.onNodeWithText("Milk").assertIsDisplayed()
    composeRule.onNodeWithText("Bread").assertIsDisplayed()
    composeRule
        .onNodeWithContentDescription("Delete Milk")
        .assertIsDisplayed()
        .assertIsEnabled()
        .performClick()
    composeRule
        .onNodeWithContentDescription("Delete Bread")
        .assertIsDisplayed()
        .assertIsEnabled()
        .performClick()
    composeRule.runOnIdle { assertEquals(listOf("milk", "bread"), requests) }
    composeRule.onNodeWithTag(C.Tag.fridge_empty).assertDoesNotExist()
  }

  @Test
  fun removeActionIsDisabledWithoutRuntimeWiring() {
    composeRule.setContent {
      MaterialTheme {
        FridgeScreen(uiState = FridgeUiState(items = listOf(Item(id = "milk", name = "Milk"))))
      }
    }
    composeRule.onNodeWithContentDescription("Delete Milk").assertIsDisplayed().assertIsNotEnabled()
  }

  @Test
  fun removalActionsAreDisabledWhileDeletionIsPending() {
    composeRule.setContent {
      MaterialTheme {
        FridgeScreen(
            uiState =
                FridgeUiState(items = listOf(Item(id = "milk", name = "Milk")), isDeleting = true),
            onRemoveItem = {})
      }
    }
    composeRule.onNodeWithContentDescription("Delete Milk").assertIsNotEnabled()
    composeRule.onNodeWithTag(C.Tag.fridge_remove_dialog).assertDoesNotExist()
  }

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
    val imageRepository =
        object : FoodImageRepository {
          override suspend fun searchByTitle(title: String) = FoodImageSearchResult.NotFound
        }
    val viewModel =
        FridgeViewModel(
            repository,
            USER_ID,
            HOUSEHOLD_ID,
            FoodImageSelector(imageRepository),
            BarcodeScanner { BarcodeScanResult.NotFound })

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
    val imageRepository =
        object : FoodImageRepository {
          override suspend fun searchByTitle(title: String) = FoodImageSearchResult.NotFound
        }
    val viewModel =
        FridgeViewModel(
            repository,
            USER_ID,
            HOUSEHOLD_ID,
            FoodImageSelector(imageRepository),
            BarcodeScanner { BarcodeScanResult.NotFound })
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
          expirationDate = ExpirationDates.toDate(LocalDate.now().plusDays(days)))

  private class FakeItemRepository(items: List<Item>) : ItemRepository {
    private val sharedItems = MutableStateFlow(items)

    override fun getPrivateItems(userId: String): Flow<List<Item>> = MutableStateFlow(emptyList())

    override fun getSharedItems(householdId: String): Flow<List<Item>> = sharedItems

    override suspend fun updateItemImageIfAbsent(
        itemId: String,
        expectedName: String,
        image: ItemImage
    ): ItemImageUpdateResult = error("Not used in this test")

    override fun updateItem(itemId: String, edit: ItemEdit): ItemWrite =
        error("Not used in this test")

    override fun setItemStatus(itemId: String, status: ItemStatus): ItemWrite =
        error("Not used in this test")

    override fun deleteItemQueued(itemId: String): ItemWrite = error("Not used in this test")

    override suspend fun addItem(item: Item): String? = error("Not used in this test.")

    override suspend fun deleteItem(itemId: String): Boolean = error("Not used in this test.")
  }

  private companion object {
    const val USER_ID = "user-1"
    const val HOUSEHOLD_ID = "household-1"
  }
}
