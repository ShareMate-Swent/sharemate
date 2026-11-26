// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.recipes

import com.android.sharemate.model.item.ExpirationDates
import com.android.sharemate.model.item.FakeItemRepository
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.item.ItemSharing
import com.android.sharemate.model.user.ExpirationPreferences
import com.android.sharemate.model.user.ExpirationPreferencesProvider
import com.android.sharemate.model.user.ExpirationPreferencesRepository
import com.android.sharemate.model.user.FakeExpirationPreferencesRepository
import java.io.IOException
import java.time.LocalDate
import java.util.Date
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeIngredientExpiryProviderTest {
  private val today = LocalDate.of(2026, 11, 23)
  private val items = FakeItemRepository()
  private val settings = FakeExpirationPreferencesRepository()
  private val preferenceReads = mutableListOf<String>()
  private val recordedSettings =
      object : ExpirationPreferencesRepository by settings {
        override suspend fun get(userId: String): ExpirationPreferences {
          preferenceReads += userId
          return settings.get(userId)
        }
      }
  private val provider = provider()

  @Test
  fun boundariesIncludeTheLimitAndCountTheExpiryDayAsExpired() = runTest {
    settings.save("alice", ExpirationPreferences(soonDays = 7))
    val offsets = listOf(-1L, 0L, 1L, 6L, 7L, 8L)
    items.emitItems(offsets.map { item("item-$it", today.plusDays(it)) })
    assertEquals(
        listOf(
            IngredientExpiryStatus.EXPIRED,
            IngredientExpiryStatus.EXPIRED,
            IngredientExpiryStatus.EXPIRES_SOON,
            IngredientExpiryStatus.EXPIRES_SOON,
            IngredientExpiryStatus.EXPIRES_SOON,
            IngredientExpiryStatus.NOT_EXPIRED_YET,
        ),
        provider.ingredients("home", "alice", today).map { it.status })
    assertEquals(listOf("alice"), preferenceReads)
  }

  @Test
  fun unknownExpirationIsNotFlaggedAsExpired() = runTest {
    items.emitItems(listOf(item("rice", null)))
    val entry = provider.ingredients("home", "alice", today).single()
    assertEquals(IngredientExpiryStatus.NOT_EXPIRED_YET, entry.status)
    assertNull(entry.ingredient.expirationDate)
  }

  @Test
  fun equalNamesKeepDifferentStockIdsAndFreshness() = runTest {
    items.emitItems(
        listOf(
            item("old", today.minusDays(1)).copy(name = " MILK "),
            item("soon", today.plusDays(1)).copy(name = "milk"),
            item("fresh", today.plusDays(10)).copy(name = "Milk"),
        ))
    val result = provider.ingredients("home", "alice", today)
    assertEquals(listOf("old", "soon", "fresh"), result.map { it.ingredient.itemId })
    assertEquals(listOf("milk", "milk", "milk"), result.map { it.ingredient.name })
    assertEquals(
        listOf(
            IngredientExpiryStatus.EXPIRED,
            IngredientExpiryStatus.EXPIRES_SOON,
            IngredientExpiryStatus.NOT_EXPIRED_YET),
        result.map { it.status })
    assertEquals(listOf("milk"), RecipeIngredientProvider(items).ingredients("home", "alice"))
  }

  @Test fun missingSettingsUseTheDocumentedDefaultBoundary() = runTest { assertDefaultBoundary() }

  @Test
  fun unavailableSettingsFallBackWithoutHidingTheInventory() = runTest {
    settings.readFailure = IOException("offline")
    assertDefaultBoundary()
  }

  @Test
  fun settingsAreReadForTheRequestedUserAndRefreshedOnEachCall() = runTest {
    settings.save("alice", ExpirationPreferences(soonDays = 7))
    settings.save("bob", ExpirationPreferences(soonDays = 1))
    items.emitItems(listOf(item("shared", today.plusDays(4)).copy(sharing = ItemSharing.SHARED)))
    assertEquals(
        IngredientExpiryStatus.EXPIRES_SOON,
        provider.ingredients("home", "alice", today).single().status)
    assertEquals(
        IngredientExpiryStatus.NOT_EXPIRED_YET,
        provider.ingredients("home", "bob", today).single().status)
    settings.save("alice", ExpirationPreferences(soonDays = 1))
    assertEquals(
        IngredientExpiryStatus.NOT_EXPIRED_YET,
        provider.ingredients("home", "alice", today).single().status)
    items.emitItems(listOf(item("new-stock", today.minusDays(1))))
    assertEquals(
        "new-stock", provider.ingredients("home", "alice", today).single().ingredient.itemId)
    assertEquals(listOf("alice", "bob", "alice", "alice"), preferenceReads)
  }

  @Test
  fun emptyOrBlankScopeDoesNotReadPreferences() = runTest {
    assertTrue(provider.ingredients("home", "alice", today).isEmpty())
    items.emitItems(listOf(item("stock", today)))
    assertTrue(provider.ingredients(" ", "alice", today).isEmpty())
    assertTrue(provider.ingredients("home", "", today).isEmpty())
    assertTrue(preferenceReads.isEmpty())
    assertEquals(listOf("home"), items.requestedHouseholdIds)
  }

  @Test
  fun inventoryFailurePropagatesInsteadOfBecomingAnEmptyFridge() = runTest {
    val failure = IOException("Inventory unavailable")
    items.readFailure = failure
    val result = runCatching { provider.ingredients("home", "alice", today) }
    assertSame(failure, result.exceptionOrNull())
    assertTrue(preferenceReads.isEmpty())
  }

  @Test
  fun preferenceCancellationIsNotConvertedToTheDefault() = runTest {
    items.emitItems(listOf(item("stock", today)))
    val cancellation = CancellationException("Cancelled read")
    settings.readFailure = cancellation
    assertSame(
        cancellation,
        runCatching { provider.ingredients("home", "alice", today) }.exceptionOrNull())
  }

  @Test
  fun cancellingAPendingInventoryReadStopsTheRequest() = runTest {
    val started = CompletableDeferred<Unit>()
    val waitingItems =
        object : ItemRepository by items {
          override suspend fun getFridgeItemsFromServer(
              householdId: String,
              userId: String
          ): List<Item> {
            started.complete(Unit)
            awaitCancellation()
          }
        }
    var completed = false
    val request = launch {
      provider(waitingItems).ingredients("home", "alice", today)
      completed = true
    }
    started.await()
    request.cancelAndJoin()
    assertTrue(request.isCancelled)
    assertFalse(completed)
    assertTrue(preferenceReads.isEmpty())
  }

  @Test
  fun cachedStocksCannotSupplyExpiryBeforeTheServerResponds() = runTest {
    val server = CompletableDeferred<List<Item>>()
    var serverReads = 0
    var cacheReads = 0
    val repository =
        object : ItemRepository by items {
          override fun getFridgeItems(householdId: String, userId: String) =
              flowOf(listOf(item("cached", today.minusDays(1)))).also { cacheReads++ }

          override suspend fun getFridgeItemsFromServer(
              householdId: String,
              userId: String
          ): List<Item> {
            serverReads++
            return server.await()
          }
        }
    val request = async { provider(repository).ingredients("home", "alice", today) }
    runCurrent()
    assertFalse(request.isCompleted)
    assertTrue(preferenceReads.isEmpty())
    server.complete(listOf(item("fresh", today.plusDays(10))))
    val result = request.await().single()
    assertEquals("fresh", result.ingredient.itemId)
    assertEquals(IngredientExpiryStatus.NOT_EXPIRED_YET, result.status)
    assertEquals(1, serverReads)
    assertEquals(0, cacheReads)
    assertEquals(listOf("alice"), preferenceReads)
  }

  @Test
  fun unavailableServerDoesNotClassifyCachedIngredients() = runTest {
    val offline = IOException("Server unavailable")
    val repository =
        object : ItemRepository by items {
          override fun getFridgeItems(householdId: String, userId: String) =
              flowOf(listOf(item("cached", today)))

          override suspend fun getFridgeItemsFromServer(
              householdId: String,
              userId: String
          ): List<Item> {
            throw offline
          }
        }
    assertSame(
        offline,
        runCatching { provider(repository).ingredients("home", "alice", today) }.exceptionOrNull())
    assertTrue(preferenceReads.isEmpty())
  }

  @Test
  fun cancellingAPendingPreferenceReadStopsTheRequest() = runTest {
    items.emitItems(listOf(item("stock", today)))
    val started = CompletableDeferred<Unit>()
    val waitingSettings =
        object : ExpirationPreferencesRepository by recordedSettings {
          override suspend fun get(userId: String): ExpirationPreferences {
            started.complete(Unit)
            awaitCancellation()
          }
        }
    var completed = false
    val request = launch {
      provider(preferences = waitingSettings).ingredients("home", "alice", today)
      completed = true
    }
    started.await()
    request.cancelAndJoin()
    assertTrue(request.isCancelled)
    assertFalse(completed)
  }

  @Test
  fun utcExpiryDaysRemainStableAcrossDeviceTimeZonesAndLeapDay() = runTest {
    val originalZone = TimeZone.getDefault()
    val leapDay = LocalDate.of(2028, 2, 29)
    try {
      for (zone in listOf("UTC", "Pacific/Honolulu", "Pacific/Kiritimati", "Europe/Zurich")) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        items.emitItems(listOf(item("leap-day", leapDay)))
        assertEquals(
            zone,
            IngredientExpiryStatus.EXPIRES_SOON,
            provider.ingredients("home", "alice", leapDay.minusDays(1)).single().status)
        assertEquals(
            zone,
            IngredientExpiryStatus.EXPIRED,
            provider.ingredients("home", "alice", leapDay.plusDays(1)).single().status)
      }
    } finally {
      TimeZone.setDefault(originalZone)
    }
  }

  @Test
  fun yearBoundaryUsesCalendarDays() = runTest {
    items.emitItems(listOf(item("new-year", LocalDate.of(2027, 1, 1))))
    assertEquals(
        IngredientExpiryStatus.EXPIRES_SOON,
        provider.ingredients("home", "alice", LocalDate.of(2026, 12, 31)).single().status)
  }

  @Test
  fun defaultTodayStillClassifiesHistoricalExpiryAsExpired() = runTest {
    items.emitItems(listOf(item("old", null).copy(expirationDate = Date(0))))
    assertEquals(
        IngredientExpiryStatus.EXPIRED, provider.ingredients("home", "alice").single().status)
  }

  private fun provider(
      inventory: ItemRepository = items,
      preferences: ExpirationPreferencesRepository = recordedSettings,
  ) =
      RecipeIngredientExpiryProvider(
          RecipeIngredientProvider(inventory), ExpirationPreferencesProvider(preferences))

  private fun item(id: String, day: LocalDate?) =
      Item(
          id = id,
          name = id,
          householdId = "home",
          ownerId = "alice",
          expirationDate = day?.let(ExpirationDates::toDate))

  private suspend fun assertDefaultBoundary() {
    val limit = ExpirationPreferences.DEFAULT_SOON_DAYS.toLong()
    items.emitItems(
        listOf(item("at-limit", today.plusDays(limit)), item("beyond", today.plusDays(limit + 1))))
    assertEquals(
        listOf(IngredientExpiryStatus.EXPIRES_SOON, IngredientExpiryStatus.NOT_EXPIRED_YET),
        provider.ingredients("home", "alice", today).map { it.status })
    assertEquals(listOf("alice"), preferenceReads)
  }
}
