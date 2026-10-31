// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.notifications.expiration

import com.android.sharemate.model.item.ExpirationDates
import com.android.sharemate.model.item.FakeItemRepository
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemSharing
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
  private var recipient: NotificationRecipient? = NotificationRecipient("alice", "home")
  private val formattedSummaries = mutableListOf<ExpirationSummary>()
  private val source =
      ExpirationNotificationSource(
          itemRepository = repository,
          recipientProvider = { recipient },
          formatter = { summary ->
            formattedSummaries += summary
            NotificationText("title", "${summary.expired.size}/${summary.expiringSoon.size}")
          },
          today = { today })

  private fun item(
      name: String,
      offsetDays: Long,
      householdId: String = "home",
      ownerId: String = "alice",
      sharing: ItemSharing = ItemSharing.SHARED
  ) =
      Item(
          id = name,
          name = name,
          ownerId = ownerId,
          householdId = householdId,
          sharing = sharing,
          expirationDate = ExpirationDates.toDate(today.plusDays(offsetDays)))

  @Test
  fun buildsOneNotificationOpeningTheFridge() = runTest {
    repository.emitItems(
        listOf(
            item("old", -1),
            item("soon", 2),
            item("ok", 30),
            item("personal", -2, sharing = ItemSharing.PERSONAL)))

    val notifications = source.dueNotifications()

    assertEquals(1, notifications.size)
    val notification = notifications.single()
    assertEquals(ExpirationNotificationSource.KEY, notification.key)
    assertEquals(AppNotificationChannel.EXPIRATION, notification.channel)
    assertEquals("title", notification.title)
    assertEquals("2/1", notification.text)
    assertEquals(AppDeepLinks.uri(TopLevelDestination.FRIDGE), notification.deepLink)
  }

  @Test
  fun readsSharedAndOwnPersonalItemsButNotOthersPersonalItems() = runTest {
    repository.emitItems(
        listOf(
            item("shared", -1),
            item("own-personal", -1, sharing = ItemSharing.PERSONAL),
            item("other-personal", -1, ownerId = "bob", sharing = ItemSharing.PERSONAL),
            item("other-fridge", -1, householdId = "elsewhere")))

    val notifications = source.dueNotifications()

    assertEquals(listOf("home"), repository.requestedHouseholdIds)
    assertEquals(listOf("alice"), repository.requestedUserIds)
    assertEquals(1, notifications.size)
    assertEquals("2/0", notifications.single().text)
  }

  @Test
  fun staysSilentWhenNothingNeedsAttention() = runTest {
    repository.emitItems(listOf(item("ok", 30)))

    assertTrue(source.dueNotifications().isEmpty())
    assertTrue(formattedSummaries.isEmpty())
  }

  @Test
  fun staysSilentWhenThereIsNoRecipient() = runTest {
    recipient = null
    repository.emitItems(listOf(item("old", -1)))

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
