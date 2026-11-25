// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.recipes

import com.android.sharemate.model.item.FakeItemRepository
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.item.ItemSharing
import com.android.sharemate.model.item.ItemStatus
import java.io.IOException
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class RecipeIngredientProviderTest {
  private val items = FakeItemRepository()
  private val provider = RecipeIngredientProvider(items)

  @Test
  fun emptyFridgeSkipsService() = runTest {
    val result = provider.suggest<String>("home", "alice") { error("Must not call service") }
    assertTrue(result.isEmpty())
    assertEquals(listOf("home"), items.requestedHouseholdIds)
    assertEquals(listOf("alice"), items.requestedUserIds)
  }

  @Test
  fun singleItemMapsToRequestWithoutMultiplyingQuantity() = runTest {
    items.emitItems(listOf(item("  TOMATO  ").copy(quantity = 4)))
    var calls = 0
    val result =
        provider.suggest("home", "alice") { names ->
          calls++
          assertEquals(listOf("tomato"), names)
          listOf("tomato soup")
        }
    assertEquals(listOf("tomato soup"), result)
    assertEquals(1, calls)
  }

  @Test
  fun mixedInventoryUsesOnlyAvailableVisibleItems() = runTest {
    items.emitItems(
        listOf(
            item("My rice"),
            item("Shared milk").copy(ownerId = "bob", sharing = ItemSharing.SHARED),
            item("Private cheese").copy(ownerId = "bob"),
            item("Other fridge").copy(householdId = "elsewhere", sharing = ItemSharing.SHARED),
            item("Eaten").copy(status = ItemStatus.EATEN),
            item("Discarded").copy(status = ItemStatus.DISCARDED),
            item("No stock").copy(quantity = 0),
            item("Negative stock").copy(quantity = -1),
            item("Expired active").copy(expirationDate = Date(0)),
            item("Unknown expiry"),
        ))
    assertEquals(
        listOf("expired active", "my rice", "shared milk", "unknown expiry"),
        provider.ingredients("home", "alice"))
  }

  @Test
  fun normalizesUnicodeWhitespaceCaseAndDuplicatesInStableOrder() = runTest {
    val previousLocale = Locale.getDefault()
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"))
      items.emitItems(
          listOf(
              item(" RICE "),
              item("rice"),
              item("\uFF32\uFF29\uFF23\uFF25"),
              item("  Crème\u00a0\u2003fraîche\n "),
              item("cre\u0300me fraîche"),
              item("\t\n\u00a0"),
              item(""),
          ))
      assertEquals(listOf("crème fraîche", "rice"), provider.ingredients("home", "alice"))
    } finally {
      Locale.setDefault(previousLocale)
    }
  }

  @Test
  fun noUsableIngredientsSkipsService() = runTest {
    items.emitItems(listOf(item(" "), item("Milk").copy(status = ItemStatus.EATEN)))
    assertTrue(provider.suggest<String>("home", "alice") { error("No request") }.isEmpty())
  }

  @Test
  fun defendsAgainstOutOfScopeItemsFromRepository() = runTest {
    val repository = mock(ItemRepository::class.java)
    `when`(repository.getFridgeItemsFromServer("home", "alice"))
        .thenReturn(
            listOf(
                item("Rice"),
                item("Bob's personal milk").copy(ownerId = "bob"),
                item("Other household").copy(householdId = "other"),
            ))
    assertEquals(listOf("rice"), RecipeIngredientProvider(repository).ingredients("home", "alice"))
  }

  @Test
  fun blankScopeSkipsRepositoryAndService() = runTest {
    for ((household, user) in listOf(" " to "alice", "home" to "")) {
      assertTrue(provider.suggest<String>(household, user) { error("No request") }.isEmpty())
    }
    assertTrue(items.requestedHouseholdIds.isEmpty())
  }

  @Test
  fun repeatedRequestsReadCurrentInventoryAndScope() = runTest {
    items.emitItems(listOf(item("Rice")))
    assertEquals(listOf("rice"), provider.ingredients("home", "alice"))
    items.emitItems(listOf(item("Milk"), item("Eggs").copy(ownerId = "bob")))
    assertEquals(listOf("milk"), provider.ingredients("home", "alice"))
    assertEquals(listOf("eggs"), provider.ingredients("home", "bob"))
    items.emitItems(emptyList())
    assertTrue(provider.ingredients("home", "bob").isEmpty())
  }

  @Test
  fun readFailureDoesNotBecomeEmptySuccessOrCallService() = runTest {
    val failure = IOException("Read denied")
    items.readFailure = failure
    try {
      provider.suggest<String>("home", "alice") { error("No request after failed read") }
      fail("Expected read failure")
    } catch (actual: IOException) {
      assertSame(failure, actual)
    }
  }

  @Test
  fun serviceCancellationPropagates() = runTest {
    items.emitItems(listOf(item("Rice")))
    val cancellation = CancellationException("Cancelled")
    try {
      provider.suggest<String>("home", "alice") { throw cancellation }
      fail("Expected cancellation")
    } catch (actual: CancellationException) {
      assertSame(cancellation, actual)
    }
  }

  private fun item(name: String) = Item(name = name, ownerId = "alice", householdId = "home")
}
