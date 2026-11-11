// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications.expiration

import com.android.sharemate.model.item.ExpirationDates
import com.android.sharemate.model.item.FakeItemRepository
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemSharing
import com.android.sharemate.model.user.ExpirationPreferences
import com.android.sharemate.model.user.ExpirationPreferencesProvider
import com.android.sharemate.model.user.FakeExpirationPreferencesRepository
import com.android.sharemate.notifications.AppNotificationChannel
import com.android.sharemate.notifications.NotificationRecipient
import com.android.sharemate.ui.navigation.AppDeepLinks
import com.android.sharemate.ui.navigation.TopLevelDestination
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpirationNotificationSourceTest {
  private val today = LocalDate.of(2026, 10, 15)
  private val repository = FakeItemRepository()
  private val preferences = FakeExpirationPreferencesRepository()
  private var recipient: NotificationRecipient? = NotificationRecipient("alice", "home")
  private val formattedSummaries = mutableListOf<ExpirationSummary>()
  private val source =
      ExpirationNotificationSource(
          itemRepository = repository,
          recipientProvider = { recipient },
          preferencesProvider = ExpirationPreferencesProvider(preferences),
          formatter = { summary ->
            formattedSummaries += summary
            NotificationText("title", "${summary.expired.size}/${summary.expiringSoon.size}")
          },
          today = { today })

  private fun sharedItem(
      name: String,
      offsetDays: Long,
      householdId: String = "home",
      ownerId: String = "alice",
      sharing: ItemSharing = ItemSharing.PERSONAL,
  ) =
      Item(
          id = name,
          name = name,
          ownerId = ownerId,
          householdId = householdId,
          expirationDate = ExpirationDates.toDate(today.plusDays(offsetDays)),
          sharing = sharing)

  @Test
  fun buildsOneNotificationOpeningTheFridge() = runTest {
    repository.emitItems(listOf(sharedItem("old", -1), sharedItem("soon", 2), sharedItem("ok", 30)))

    val notifications = source.dueNotifications()

    assertEquals(1, notifications.size)
    val notification = notifications.single()
    assertEquals(ExpirationNotificationSource.KEY, notification.key)
    assertEquals(AppNotificationChannel.EXPIRATION, notification.channel)
    assertEquals("title", notification.title)
    assertEquals("1/1", notification.text)
    assertEquals(AppDeepLinks.uri(TopLevelDestination.FRIDGE), notification.deepLink)
  }

  @Test
  fun usesThePreferencesOfTheRecipient() = runTest {
    // Soon window of 10 days: an item 10 days away is reminded, though the default (3) would not.
    preferences.save("alice", ExpirationPreferences(soonDays = 10))
    repository.emitItems(listOf(sharedItem("far", 10)))

    assertEquals(1, source.dueNotifications().size)
  }

  @Test
  fun staysSilentOnADayThatIsNotDueForThePreferences() = runTest {
    preferences.save("alice", ExpirationPreferences(expiredRepeatDays = 5))
    repository.emitItems(listOf(sharedItem("old", -2)))

    assertTrue(source.dueNotifications().isEmpty())
  }

  @Test
  fun readsTheFridgeOfTheRecipient() = runTest {
    repository.emitItems(listOf(sharedItem("other", -1, householdId = "elsewhere")))

    val notifications = source.dueNotifications()

    assertEquals(listOf("home"), repository.requestedHouseholdIds)
    assertEquals(listOf("alice"), repository.requestedUserIds)
    assertTrue(notifications.isEmpty())
  }

  @Test
  fun neverMentionsThePersonalItemsOfOtherMembers() = runTest {
    repository.emitItems(listOf(sharedItem("theirs", -1, ownerId = "bob")))
    assertTrue(source.dueNotifications().isEmpty())

    repository.emitItems(
        listOf(
            sharedItem("theirs", -1, ownerId = "bob"),
            sharedItem("shared", -1, ownerId = "bob", sharing = ItemSharing.SHARED),
            sharedItem("mine", 2, ownerId = "alice")))

    assertEquals("1/1", source.dueNotifications().single().text)
  }

  @Test
  fun staysSilentWhenNothingNeedsAttention() = runTest {
    repository.emitItems(listOf(sharedItem("ok", 30)))

    assertTrue(source.dueNotifications().isEmpty())
    assertTrue(formattedSummaries.isEmpty())
  }

  @Test
  fun staysSilentWhenThereIsNoRecipient() = runTest {
    recipient = null
    repository.emitItems(listOf(sharedItem("old", -1)))

    assertTrue(source.dueNotifications().isEmpty())
    assertTrue(repository.requestedHouseholdIds.isEmpty())
  }

  @Test
  fun propagatesRepositoryFailures() = runTest {
    repository.readFailure = IllegalStateException("offline")

    try {
      source.dueNotifications()
      throw AssertionError("Expected the repository failure")
    } catch (expected: IllegalStateException) {
      assertEquals("offline", expected.message)
    }
  }
}
