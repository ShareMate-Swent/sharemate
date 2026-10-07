// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.fridge

import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemRepository
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FridgeViewModelTest {
  private lateinit var repository: FakeItemRepository
  private lateinit var viewModel: FridgeViewModel

  @Before
  fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
    repository = FakeItemRepository()
    viewModel = FridgeViewModel(repository, HOUSEHOLD_ID)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun observesSharedItemsForHouseholdAndSortsByExpirationDate() = runTest {
    val later = item("later", expirationDate = Date(2_000))
    val undated = item("undated")
    val sooner = item("sooner", expirationDate = Date(1_000))
    val items = listOf(later, undated, sooner)
    val collection =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          viewModel.visibleItems.collect()
        }

    repository.sharedItems.value = items
    advanceUntilIdle()

    assertEquals(listOf(HOUSEHOLD_ID), repository.requestedHouseholdIds)
    assertEquals(listOf(sooner, later, undated), viewModel.visibleItems.value)
    collection.cancel()
  }

  @Test
  fun sortsItemsByNameWithoutCaseSensitivity() = runTest {
    val banana = item("banana", name = "banana")
    val apple = item("apple", name = "Apple")
    val carrot = item("carrot", name = "carrot")
    val collection =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          viewModel.visibleItems.collect()
        }
    repository.sharedItems.value = listOf(banana, carrot, apple)
    viewModel.setSortOrder(FridgeSortOrder.NAME)

    advanceUntilIdle()

    assertEquals(listOf(apple, banana, carrot), viewModel.visibleItems.value)
    collection.cancel()
  }

  @Test
  fun filtersCategoryIgnoringCaseAndWhitespaceAndCanClearFilter() = runTest {
    val milk = item("milk", category = " Dairy ")
    val apple = item("apple", category = "Fruit")
    val collection =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          viewModel.visibleItems.collect()
        }
    repository.sharedItems.value = listOf(milk, apple)
    viewModel.setCategoryFilter(" dairy ")

    advanceUntilIdle()

    assertEquals(listOf(milk), viewModel.visibleItems.value)

    viewModel.setCategoryFilter("")
    advanceUntilIdle()

    assertNull(viewModel.selectedCategory.value)
    assertEquals(listOf(milk, apple), viewModel.visibleItems.value)
    collection.cancel()
  }

  @Test
  fun combinesOwnerAndCategoryFilters() = runTest {
    val matching = item("matching", category = "Dairy", ownerId = "owner-1")
    val otherOwner = item("other-owner", category = "Dairy", ownerId = "owner-2")
    val otherCategory = item("other-category", category = "Fruit", ownerId = "owner-1")
    val collection =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          viewModel.visibleItems.collect()
        }
    repository.sharedItems.value = listOf(matching, otherOwner, otherCategory)
    viewModel.setCategoryFilter("dairy")
    viewModel.setOwnerFilter("owner-1")

    advanceUntilIdle()

    assertEquals(listOf(matching), viewModel.visibleItems.value)
    collection.cancel()
  }

  @Test
  fun reactsToRepositoryAndFilterUpdates() = runTest {
    val initialItems = listOf(item("milk", category = "Dairy"), item("apple", category = "Fruit"))
    val emittedItems = mutableListOf<List<Item>>()
    val collection =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          viewModel.visibleItems.collect(emittedItems::add)
        }
    runCurrent()

    repository.sharedItems.value = initialItems
    runCurrent()
    viewModel.setCategoryFilter("Fruit")
    runCurrent()
    repository.sharedItems.value = initialItems + item("yogurt", category = "Fruit")
    runCurrent()

    assertEquals(
        listOf(
            emptyList(),
            listOf("milk", "apple"),
            listOf("apple"),
            listOf("apple", "yogurt"),
        ),
        emittedItems.map { items -> items.map(Item::id) },
    )
    collection.cancel()
  }

  private fun item(
      id: String,
      name: String = id,
      expirationDate: Date? = null,
      category: String? = null,
      ownerId: String = "",
  ) =
      Item(
          id = id,
          name = name,
          expirationDate = expirationDate,
          category = category,
          ownerId = ownerId,
      )

  private class FakeItemRepository : ItemRepository {
    val sharedItems = MutableStateFlow<List<Item>>(emptyList())
    val requestedHouseholdIds = mutableListOf<String>()

    override fun getPrivateItems(userId: String): Flow<List<Item>> = MutableStateFlow(emptyList())

    override fun getSharedItems(householdId: String): Flow<List<Item>> {
      requestedHouseholdIds += householdId
      return sharedItems
    }

    override suspend fun addItem(item: Item): String =
        error("Adding items is not used by the fridge ViewModel.")

    override suspend fun deleteItem(itemId: String): Boolean =
        error("Deleting items is not used by the fridge ViewModel.")
  }

  private companion object {
    const val HOUSEHOLD_ID = "household-1"
  }
}
