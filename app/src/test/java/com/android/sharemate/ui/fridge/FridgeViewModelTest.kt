// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.fridge

import com.android.sharemate.model.item.Item
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FridgeViewModelTest {
  private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
  private val viewModel = FridgeViewModel(testScope)
  private val visibleItemsSubscription = testScope.launch { viewModel.visibleItems.collect() }

  @After
  fun cancelTestScope() {
    testScope.cancel()
  }

  @Test
  fun itemsAreSortedByExpirationDateWithItemsWithoutDateLast() {
    val later = item("later", expirationDate = Date(2_000))
    val undated = item("undated")
    val sooner = item("sooner", expirationDate = Date(1_000))

    viewModel.updateItems(listOf(later, undated, sooner))

    assertEquals(listOf(sooner, later, undated), viewModel.visibleItems.value)
  }

  @Test
  fun itemsCanBeSortedByNameWithoutCaseSensitivity() {
    val banana = item("banana", name = "banana")
    val apple = item("apple", name = "Apple")
    val carrot = item("carrot", name = "carrot")
    viewModel.updateItems(listOf(banana, carrot, apple))

    viewModel.setSortOrder(FridgeSortOrder.NAME)

    assertEquals(listOf(apple, banana, carrot), viewModel.visibleItems.value)
  }

  @Test
  fun categoryFilterIsCaseInsensitiveAndCanBeCleared() {
    val milk = item("milk", category = "Dairy")
    val apple = item("apple", category = "Fruit")
    viewModel.updateItems(listOf(milk, apple))

    viewModel.setCategoryFilter(" dairy ")

    assertEquals(listOf(milk), viewModel.visibleItems.value)

    viewModel.setCategoryFilter("")

    assertNull(viewModel.selectedCategory.value)
    assertEquals(listOf(milk, apple), viewModel.visibleItems.value)
  }

  @Test
  fun ownerAndCategoryFiltersCanBeCombined() {
    val matching = item("matching", category = "Dairy", ownerId = "owner-1")
    val otherOwner = item("other-owner", category = "Dairy", ownerId = "owner-2")
    val otherCategory = item("other-category", category = "Fruit", ownerId = "owner-1")
    viewModel.updateItems(listOf(matching, otherOwner, otherCategory))

    viewModel.setCategoryFilter("dairy")
    viewModel.setOwnerFilter("owner-1")

    assertEquals(listOf(matching), viewModel.visibleItems.value)
  }

  @Test
  fun stateFlowEmitsUpdatesWhenItemsAndFiltersChange() = runBlocking {
    val initialItems = listOf(item("milk", category = "Dairy"), item("apple", category = "Fruit"))
    val emittedStates = mutableListOf<List<Item>>()
    val collection =
        launch(Dispatchers.Unconfined) { viewModel.visibleItems.collect(emittedStates::add) }
    try {
      viewModel.updateItems(initialItems)
      viewModel.setCategoryFilter("Fruit")
      viewModel.updateItems(initialItems + item("yogurt", category = "Fruit"))
    } finally {
      collection.cancel()
    }

    assertEquals(4, emittedStates.size)
    assertEquals(listOf("apple"), emittedStates[2].map(Item::id))
    assertEquals(listOf("apple", "yogurt"), emittedStates[3].map(Item::id))
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
}
