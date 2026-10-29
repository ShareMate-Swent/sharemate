// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.notifications.expiration

import com.android.sharemate.model.item.ExpirationDates
import com.android.sharemate.model.item.FakeItemRepository
import com.android.sharemate.model.item.Item
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

  private fun sharedItem(name: String, offsetDays: Long, householdId: String = "home") =
      Item(
          id = name,
          name = name,
          ownerId = "alice",
          householdId = householdId,
          expirationDate = ExpirationDates.toDate(today.plusDays(offsetDays)))

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
  fun readsTheHouseholdsSharedItems() = runTest {
    repository.emitItems(listOf(sharedItem("other", -1, householdId = "elsewhere")))

    val notifications = source.dueNotifications()

    assertEquals(listOf("home"), repository.requestedHouseholdIds)
    assertTrue(notifications.isEmpty())
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
