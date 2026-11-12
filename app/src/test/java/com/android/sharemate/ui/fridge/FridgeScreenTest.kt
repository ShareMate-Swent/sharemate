// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.fridge

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import com.android.sharemate.R
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.image.FoodImageRepository
import com.android.sharemate.model.image.FoodImageSearchResult
import com.android.sharemate.model.image.FoodImageSelector
import com.android.sharemate.model.item.ExpirationDates
import com.android.sharemate.model.item.ExpiryStatus
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.item.InventorySubmissionStatus
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemEdit
import com.android.sharemate.model.item.ItemImage
import com.android.sharemate.model.item.ItemImageUpdateResult
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.item.ItemStatus
import com.android.sharemate.model.item.ItemWrite
import com.android.sharemate.model.user.ExpirationPreferencesProvider
import com.android.sharemate.model.user.FakeExpirationPreferencesRepository
import com.android.sharemate.model.user.FakePseudoRepository
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(sdk = [28], qualifiers = "w360dp-h800dp")
@RunWith(RobolectricTestRunner::class)
class FridgeScreenTest {
  @get:Rule val composeRule = createComposeRule()

  private val testApplication
    get() = RuntimeEnvironment.getApplication()

  @Test
  fun phoneGridHasThreeSquareTilesPerRowAndRealMetadata() {
    val items =
        (1..4).map {
          Item(id = "$it", name = "Food $it", quantity = it, householdId = "household-1")
        }
    composeRule.setContent {
      MaterialTheme {
        FridgeScreen(
            modifier = androidx.compose.ui.Modifier.width(360.dp),
            uiState = FridgeUiState(items = items, fridgeType = FridgeType.SHARED),
            expiryStatuses = emptyMap())
      }
    }
    val squares =
        items.map {
          composeRule
              .onNodeWithTag(C.Tag.fridgeProductSquare(it.id))
              .assertIsDisplayed()
              .fetchSemanticsNode()
              .boundsInRoot
        }
    squares.forEach { assertEquals(it.width, it.height, 1f) }
    assertEquals(squares[0].top, squares[1].top, 1f)
    assertEquals(squares[1].top, squares[2].top, 1f)
    assertTrue(squares[0].right < squares[1].left)
    assertTrue(squares[1].right < squares[2].left)
    assertEquals(squares[0].left, squares[3].left, 1f)
    assertTrue(squares[3].top > squares[0].bottom)
    composeRule.onAllNodesWithTag(C.Tag.fridge_image_placeholder).assertCountEquals(4)
    items.forEach {
      composeRule.onNodeWithText(it.name).assertIsDisplayed()
      composeRule
          .onNodeWithText("Qty: ${it.quantity} ${if (it.quantity == 1) "unit" else "units"}")
          .assertIsDisplayed()
      composeRule.onNodeWithTag(C.Tag.fridgeProductSquare(it.id)).assertIsDisplayed()
    }
  }

  @Test
  fun typeLabelFollowsTheFridgeType() {
    val type = mutableStateOf(FridgeType.PRIVATE)
    composeRule.setContent {
      MaterialTheme {
        FridgeScreen(uiState = FridgeUiState(fridgeType = type.value), expiryStatuses = emptyMap())
      }
    }
    composeRule
        .onNodeWithTag(C.Tag.fridge_type)
        .assertIsDisplayed()
        .assertTextEquals("Fridge type: Private")
    composeRule.runOnIdle { type.value = FridgeType.SHARED }
    composeRule
        .onNodeWithTag(C.Tag.fridge_type)
        .assertIsDisplayed()
        .assertTextEquals("Fridge type: Shared")
  }

  @Test
  fun statusActionsAreAccessibleAndForwardTheCorrectLifecycleValue() {
    val changes = mutableListOf<ItemStatus>()
    composeRule.setContent {
      MaterialTheme {
        FridgeItem(
            item = Item(id = "milk", name = "Milk", householdId = "household-1"),
            statusEnabled = true,
            onStatusChange = { changes += it },
            expiryStatus = ExpiryStatus.NORMAL,
            ownership = ItemOwnership(com.android.sharemate.model.item.ItemSharing.PERSONAL),
        )
      }
    }
    openActions("milk")
    composeRule.onNodeWithContentDescription("Mark Milk as Eaten").assertIsEnabled().performClick()
    openActions("milk")
    composeRule
        .onNodeWithContentDescription("Mark Milk as Thrown away")
        .assertIsEnabled()
        .performClick()
    assertEquals(listOf(ItemStatus.EATEN, ItemStatus.DISCARDED), changes)
  }

  @Test
  fun activeScreenHidesHistoryAndDisablesActionsDuringLocalSubmission() {
    val item = Item(id = "milk", name = "Milk", householdId = "household-1")
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
                    pendingStatusIds = setOf(item.id),
                    fridgeType = FridgeType.SHARED),
            onStatusChange = { _, _ -> },
            onEditItem = {},
            expiryStatuses = emptyMap())
      }
    }
    openActions(item.id)
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
    val milk = Item(id = "milk", name = "Milk", ownerId = USER_ID, householdId = HOUSEHOLD_ID)
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
            testBarcodeScanner(),
            testProductCatalogRepository(),
            expirationPreferences =
                ExpirationPreferencesProvider(FakeExpirationPreferencesRepository()),
            fridgeType = FridgeType.SHARED,
            memberNameRepository = testMemberNameRepository(),
            currentUserDisplayName = null,
            pseudoRepository = testPseudoRepository(),
        )
    val store = ViewModelStore().also { it.put("fridge", viewModel) }
    try {
      composeRule.setContent { MaterialTheme { FridgeScreen(viewModel) } }
      openActions("milk")
      composeRule.onNodeWithContentDescription("Delete Milk").performClick()
      composeRule.onNodeWithTag(C.Tag.fridge_remove_dialog).assertIsDisplayed()
      val prompt = "Permanently delete Milk from the fridge? This cannot be undone."
      composeRule.onNodeWithText(prompt).assertIsDisplayed()
      composeRule.onNodeWithTag(C.Tag.fridge_cancel_remove).performClick()
      composeRule.onNodeWithTag(C.Tag.fridge_remove_dialog).assertDoesNotExist()
      composeRule.runOnIdle { assertEquals(emptyList<String>(), repository.deletedItemIds) }
      openActions("milk")
      composeRule.onNodeWithContentDescription("Delete Milk").performClick()
      composeRule.onNodeWithTag(C.Tag.fridge_confirm_remove).performClick()
      composeRule.onNodeWithTag(C.Tag.fridge_remove_dialog).assertDoesNotExist()
      composeRule.onNodeWithTag(C.Tag.fridge_item_prefix + milk.id).assertDoesNotExist()
      composeRule.onNodeWithText("Bread").assertIsDisplayed()
      assertEquals(listOf(milk.id), repository.queuedDeletionIds)
      assertEquals(false, confirmation.isCompleted)
      openActions(bread.id)
      composeRule.onNodeWithTag(C.Tag.fridge_edit_prefix + bread.id).performClick()
      assertEquals(bread.id, viewModel.uiState.value.editingItemId)
      composeRule.runOnUiThread { viewModel.dismissAddItemDialog() }
      openActions(bread.id)
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
                removalSyncError = error,
                pendingRemovalItem =
                    Item(id = "bread", name = "Bread", householdId = "household-1"),
                fridgeType = FridgeType.SHARED))
    val dismissed = mutableListOf<FridgeRemovalSyncError>()
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
      MaterialTheme {
        FridgeScreen(
            uiState = state.value,
            onDismissRemovalSyncError = { dismissed += it },
            expiryStatuses = emptyMap())
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
    val items =
        listOf(
            Item(id = "milk", name = "Milk", householdId = "household-1"),
            Item(id = "bread", name = "Bread", householdId = "household-1"))
    composeRule.setContent {
      MaterialTheme {
        FridgeScreen(
            uiState = FridgeUiState(items = items, fridgeType = FridgeType.SHARED),
            onRemoveItem = { requests += it },
            expiryStatuses = emptyMap())
      }
    }

    composeRule.onNodeWithText("Milk").assertIsDisplayed()
    composeRule.onNodeWithText("Bread").assertIsDisplayed()
    openActions("milk")
    composeRule
        .onNodeWithContentDescription("Delete Milk")
        .assertIsDisplayed()
        .assertIsEnabled()
        .performClick()
    openActions("bread")
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
        FridgeScreen(
            uiState =
                FridgeUiState(
                    items = listOf(Item(id = "milk", name = "Milk", householdId = "household-1")),
                    fridgeType = FridgeType.SHARED),
            expiryStatuses = emptyMap())
      }
    }
    openActions("milk")
    composeRule.onNodeWithContentDescription("Delete Milk").assertIsDisplayed().assertIsNotEnabled()
  }

  @Test
  fun removalActionsAreDisabledWhileDeletionIsPending() {
    composeRule.setContent {
      MaterialTheme {
        FridgeScreen(
            uiState =
                FridgeUiState(
                    items = listOf(Item(id = "milk", name = "Milk", householdId = "household-1")),
                    isDeleting = true,
                    fridgeType = FridgeType.SHARED),
            onRemoveItem = {},
            expiryStatuses = emptyMap())
      }
    }
    openActions("milk")
    composeRule.onNodeWithContentDescription("Delete Milk").assertIsNotEnabled()
    composeRule.onNodeWithTag(C.Tag.fridge_remove_dialog).assertDoesNotExist()
  }

  @Test
  fun defaultScreenShowsInventoryAndDisabledCategoryControls() {
    composeRule.setContent {
      MaterialTheme {
        FridgeScreen(
            uiState = FridgeUiState(fridgeType = FridgeType.SHARED), expiryStatuses = emptyMap())
      }
    }

    composeRule.onNodeWithTag(C.Tag.fridge_title).assertIsDisplayed()
    composeRule.onNodeWithTag(C.Tag.fridge_empty).assertIsDisplayed()
    composeRule
        .onNodeWithText(testApplication.getString(R.string.fridge_category_all))
        .assertIsDisplayed()
    composeRule
        .onNodeWithTag(C.Tag.fridge_filter_category_all)
        .assertIsSelected()
        .assertIsNotEnabled()
    composeRule
        .onNodeWithText(testApplication.getString(R.string.fridge_sort))
        .assertIsDisplayed()
        .assertIsNotEnabled()
    composeRule
        .onNodeWithText(testApplication.getString(R.string.fridge_yours))
        .assertIsDisplayed()
        .assertIsNotEnabled()
        .assertHasNoClickAction()
        .assertIsNotSelected()
    composeRule
        .onNodeWithText(testApplication.getString(R.string.fridge_shared))
        .assertIsDisplayed()
        .assertIsNotEnabled()
        .assertHasNoClickAction()
        .assertIsNotSelected()
    composeRule
        .onNodeWithContentDescription(testApplication.getString(R.string.fridge_view_options))
        .assertIsNotEnabled()
    composeRule.onNodeWithTag(C.Tag.fridge_add_item).assertIsNotEnabled()
  }

  @Test
  fun sortAndFilterControlsUpdateViewModel() {
    val repository =
        FakeItemRepository(
            listOf(
                Item(
                    id = "milk",
                    name = "Milk",
                    category = "Dairy",
                    ownerId = "owner-1",
                    householdId = "household-1"),
                Item(
                    id = "bread",
                    name = "Bread",
                    category = "Bakery",
                    ownerId = "owner-2",
                    householdId = "household-1")))
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
            testBarcodeScanner(),
            testProductCatalogRepository(),
            pseudoRepository = FakePseudoRepository(mapOf("owner-1" to "Alice")),
            expirationPreferences =
                ExpirationPreferencesProvider(FakeExpirationPreferencesRepository()),
            fridgeType = FridgeType.SHARED,
            memberNameRepository = testMemberNameRepository(mapOf("owner-1" to "Alice")),
            currentUserDisplayName = null,
        )

    composeRule.setContent { MaterialTheme { FridgeScreen(viewModel) } }
    composeRule.waitForIdle()

    composeRule.onNodeWithText(testApplication.getString(R.string.fridge_sort)).performClick()
    composeRule
        .onNodeWithText(testApplication.getString(R.string.fridge_sort_expiration_date))
        .assertIsSelected()
    composeRule
        .onNodeWithText(testApplication.getString(R.string.fridge_sort_name))
        .assertIsNotSelected()
        .performClick()
    assertEquals(FridgeSortOrder.NAME, viewModel.sortOrder.value)
    composeRule
        .onNodeWithText(testApplication.getString(R.string.fridge_sort_expiration_date))
        .assertDoesNotExist()

    composeRule.onNodeWithTag(C.Tag.fridgeFilterCategory("Dairy")).performScrollTo().performClick()
    assertEquals("Dairy", viewModel.selectedCategory.value)

    composeRule
        .onNodeWithText(testApplication.getString(R.string.fridge_owner_filter))
        .performClick()
    composeRule.onNodeWithText("Alice").assertExists()
    composeRule.onNodeWithText("Unknown member (15321b2a)").assertExists()
    composeRule.onNodeWithText("owner-1").assertDoesNotExist()
    composeRule.onNodeWithTag(C.Tag.fridgeFilterOwner("owner-1")).performClick()
    assertEquals("owner-1", viewModel.selectedOwnerId.value)
    composeRule.onNodeWithContentDescription("Owner: Alice").performClick()
    composeRule.onNodeWithTag(C.Tag.fridge_filter_owner_all).performClick()
    assertEquals(null, viewModel.selectedOwnerId.value)
    assertEquals("Dairy", viewModel.selectedCategory.value)

    composeRule.onNodeWithText(testApplication.getString(R.string.fridge_sort)).performClick()
    composeRule
        .onNodeWithText(testApplication.getString(R.string.fridge_sort_name))
        .assertIsSelected()
    composeRule
        .onNodeWithText(testApplication.getString(R.string.fridge_sort_expiration_date))
        .assertIsNotSelected()
        .performClick()
    assertEquals(FridgeSortOrder.EXPIRATION_DATE, viewModel.sortOrder.value)
  }

  @Test
  fun unknownOwnersWithCollidingCodesRemainDistinctAndFilterById() {
    val first = "unknown-100013"
    val second = "unknown-20928"
    val repository =
        FakeItemRepository(
            listOf(
                Item(id = "milk", name = "Milk", ownerId = first, householdId = HOUSEHOLD_ID),
                Item(id = "bread", name = "Bread", ownerId = second, householdId = HOUSEHOLD_ID)))
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
            testBarcodeScanner(),
            testProductCatalogRepository(),
            expirationPreferences =
                ExpirationPreferencesProvider(FakeExpirationPreferencesRepository()),
            fridgeType = FridgeType.SHARED,
            memberNameRepository = testMemberNameRepository(),
            currentUserDisplayName = null,
            pseudoRepository = testPseudoRepository(),
        )
    composeRule.setContent { MaterialTheme { FridgeScreen(viewModel) } }
    composeRule.waitForIdle()

    composeRule
        .onNodeWithText(testApplication.getString(R.string.fridge_owner_filter))
        .performClick()
    composeRule.onNodeWithText("Unknown member (97dcd513-1)").assertExists()
    composeRule.onNodeWithText("Unknown member (97dcd513-2)").assertExists()
    composeRule.onNodeWithText(first).assertDoesNotExist()
    composeRule.onNodeWithText(second).assertDoesNotExist()
    composeRule.onNodeWithTag(C.Tag.fridgeFilterOwner(first)).performClick()
    assertEquals(first, viewModel.selectedOwnerId.value)
    composeRule.onNodeWithText("Milk").assertExists()
    composeRule.onNodeWithText("Bread").assertDoesNotExist()
    composeRule.onNodeWithText("Owner: Unknown member (97dcd513-1)").assertExists().performClick()
    composeRule.onNodeWithTag(C.Tag.fridgeFilterOwner(second)).performClick()
    assertEquals(second, viewModel.selectedOwnerId.value)
    composeRule.onNodeWithText("Bread").assertExists()
    composeRule.onNodeWithText("Milk").assertDoesNotExist()
    composeRule.onNodeWithText("Owner: Unknown member (97dcd513-2)").assertExists()
  }

  @Test
  fun ownerFallbackCodesAreDistinctAndStableForShortAndCollidingIds() {
    val ids = listOf("a", "b", "unknown-20928", "unknown-100013")
    val codes = ownerFallbackCodes(ids)

    assertEquals(ids.size, codes.values.toSet().size)
    assertEquals(codes, ownerFallbackCodes(ids.reversed()))
    assertEquals("ca978112", codes["a"])
    assertEquals("3e23e816", codes["b"])
    assertEquals("97dcd513-1", codes["unknown-100013"])
    assertEquals("97dcd513-2", codes["unknown-20928"])
  }

  @Test
  fun categoryChipRemainsUniqueWhenItemLabelsMatch() {
    val repository =
        FakeItemRepository(
            listOf(
                Item(
                    id = "apple", name = "Apple", category = "Fruits", householdId = "household-1"),
                Item(
                    id = "banana",
                    name = "Banana",
                    category = "Fruits",
                    householdId = "household-1"),
                Item(
                    id = "carrot",
                    name = "Carrot",
                    category = "Vegetables",
                    householdId = "household-1")))
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
            testBarcodeScanner(),
            testProductCatalogRepository(),
            expirationPreferences =
                ExpirationPreferencesProvider(FakeExpirationPreferencesRepository()),
            fridgeType = FridgeType.SHARED,
            memberNameRepository = testMemberNameRepository(),
            currentUserDisplayName = null,
            pseudoRepository = testPseudoRepository(),
        )
    composeRule.setContent { MaterialTheme { FridgeScreen(viewModel) } }

    for (category in listOf("Fruits", "Vegetables")) {
      composeRule
          .onNodeWithTag(C.Tag.fridgeFilterCategory(category))
          .performScrollTo()
          .performClick()
      composeRule.onAllNodesWithText("Category: $category").assertCountEquals(0)
      composeRule.onNodeWithTag(C.Tag.fridgeFilterCategory(category)).assertIsSelected()
      composeRule.onAllNodesWithText(category).assertCountEquals(1)
      composeRule.onNodeWithTag(C.Tag.fridge_filter_category_all).performScrollTo().performClick()
      composeRule.onNodeWithTag(C.Tag.fridgeFilterCategory(category)).assertIsNotSelected()
      composeRule.onNodeWithTag(C.Tag.fridge_filter_category_all).assertIsSelected()
      assertEquals(null, viewModel.selectedCategory.value)
    }
  }

  @Test
  fun allRealCategoriesRemainReachableAndSelectedFiltersCanBeCleared() {
    val selectedCategory = mutableStateOf<String?>(null)
    val categories = (1..12).map { "Category $it" }
    composeRule.setContent {
      MaterialTheme {
        FridgeScreen(
            uiState = FridgeUiState(fridgeType = FridgeType.SHARED),
            sortOrder = FridgeSortOrder.NAME,
            categories = categories,
            selectedCategory = selectedCategory.value,
            onSortOrderChange = {},
            onCategoryFilterChange = { selectedCategory.value = it },
            onOwnerFilterChange = {},
            expiryStatuses = emptyMap())
      }
    }
    composeRule.onNodeWithTag(C.Tag.fridge_filter_category_all).assertIsSelected()
    composeRule
        .onNodeWithTag(C.Tag.fridgeFilterCategory(categories.last()))
        .performScrollTo()
        .assertIsDisplayed()
        .performClick()
        .assertIsSelected()
    assertEquals(categories.last(), selectedCategory.value)
    composeRule.onNodeWithTag(C.Tag.fridge_filter_category_all).performScrollTo().performClick()
    assertEquals(null, selectedCategory.value)
    composeRule.onNodeWithTag(C.Tag.fridge_filter_category_all).assertIsSelected()
    composeRule
        .onNodeWithText(testApplication.getString(R.string.fridge_yours))
        .assertIsNotEnabled()
    composeRule
        .onNodeWithText(testApplication.getString(R.string.fridge_shared))
        .assertIsNotEnabled()
  }

  @Test
  fun compactAddControlPreservesCallbackAndSavingState() {
    val isSaving = mutableStateOf(false)
    var requests = 0
    composeRule.setContent {
      MaterialTheme {
        FridgeScreen(
            uiState = FridgeUiState(isSaving = isSaving.value, fridgeType = FridgeType.SHARED),
            onAddItem = { requests++ },
            expiryStatuses = emptyMap())
      }
    }
    composeRule.onNodeWithTag(C.Tag.fridge_add_item).assertIsEnabled().performClick()
    assertEquals(1, requests)
    composeRule.runOnIdle { isSaving.value = true }
    composeRule.onNodeWithTag(C.Tag.fridge_add_item).assertIsNotEnabled()
    composeRule.runOnIdle { isSaving.value = false }
    composeRule.onNodeWithTag(C.Tag.fridge_add_item).assertIsEnabled().performClick()
    assertEquals(2, requests)
  }

  @Test
  fun highlightsExpiredAndSoonToExpireItems() {
    composeRule.setContent {
      MaterialTheme {
        Column {
          FridgeItem(
              item = itemExpiringIn(-1),
              modifier = androidx.compose.ui.Modifier.width(100.dp),
              expiryStatus = ExpiryStatus.EXPIRED,
              ownership = ItemOwnership(com.android.sharemate.model.item.ItemSharing.PERSONAL),
          )
          FridgeItem(
              item = itemExpiringIn(3).copy(id = "soon"),
              modifier = androidx.compose.ui.Modifier.width(100.dp),
              expiryStatus = ExpiryStatus.SOON,
              ownership = ItemOwnership(com.android.sharemate.model.item.ItemSharing.PERSONAL),
          )
        }
      }
    }

    composeRule.onNodeWithContentDescription("Expired:", substring = true).assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Expires soon:", substring = true).assertIsDisplayed()
  }

  @Test
  fun doesNotHighlightAnItemWhoseStatusIsNormal() {
    composeRule.setContent {
      MaterialTheme {
        FridgeItem(
            item = itemExpiringIn(4),
            modifier = androidx.compose.ui.Modifier.width(100.dp),
            expiryStatus = ExpiryStatus.NORMAL,
            ownership = ItemOwnership(com.android.sharemate.model.item.ItemSharing.PERSONAL),
        )
      }
    }

    composeRule.onNodeWithText("Exp.: ", substring = true).assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Expires soon:", substring = true).assertDoesNotExist()
  }

  @Test
  fun expirationCardKeepsUtcDayInWesternTimeZone() {
    val originalZone = java.util.TimeZone.getDefault()
    try {
      java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Honolulu"))
      val expiration = ExpirationDates.toDate(LocalDate.now().plusDays(4))
      composeRule.setContent {
        MaterialTheme {
          FridgeItem(
              item =
                  Item(
                      id = "utc-item",
                      name = "Milk",
                      expirationDate = expiration,
                      householdId = "household-1"),
              modifier = androidx.compose.ui.Modifier.width(100.dp),
              expiryStatus = ExpiryStatus.NORMAL,
              ownership = ItemOwnership(com.android.sharemate.model.item.ItemSharing.PERSONAL),
          )
        }
      }
      composeRule
          .onNodeWithText("Exp.: ${ExpirationDates.formatCompact(expiration)}")
          .assertIsDisplayed()
      composeRule
          .onNodeWithContentDescription("Expires soon:", substring = true)
          .assertDoesNotExist()
      composeRule.onNodeWithContentDescription("Expired:", substring = true).assertDoesNotExist()
    } finally {
      java.util.TimeZone.setDefault(originalZone)
    }
  }

  private fun openActions(id: String) {
    composeRule.onNodeWithTag(C.Tag.fridgeItemActions(id)).performClick()
  }

  private fun itemExpiringIn(days: Long) =
      Item(
          id = "milk",
          name = "Milk",
          expirationDate = ExpirationDates.toDate(LocalDate.now().plusDays(days)),
          householdId = "household-1")

  private class FakeItemRepository(items: List<Item>) : ItemRepository {
    private val sharedItems = MutableStateFlow(items)

    override fun getFridgeItems(householdId: String, userId: String): Flow<List<Item>> = sharedItems

    override fun addItems(submission: InventorySubmission): ItemWrite =
        error("Batch saves are not used in this test")

    override fun observeSubmission(
        submission: InventorySubmission
    ): kotlinx.coroutines.flow.Flow<InventorySubmissionStatus> =
        error("Submission observation is not used in this test")

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

    override suspend fun addItem(item: Item): String? = error("Not used in this test.")

    override suspend fun deleteItem(itemId: String): Boolean = error("Not used in this test.")
  }

  private companion object {
    const val USER_ID = "user-1"
    const val HOUSEHOLD_ID = "household-1"
  }
}
