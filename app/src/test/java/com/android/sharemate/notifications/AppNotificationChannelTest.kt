// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications

import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.android.sharemate.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AppNotificationChannelTest {
  private val context = ApplicationProvider.getApplicationContext<Application>()
  private val manager = context.getSystemService(NotificationManager::class.java)
  private val publisher = AndroidNotificationPublisher(context, MainActivity::class.java)

  private fun chatNotification() =
      AppNotification(
          key = "chat",
          channel = AppNotificationChannel.CHAT,
          title = "Alice",
          text = "Who bought the milk?",
          deepLink = "sharemate://app/chat")

  @Test
  fun channelIdsAreUnique() {
    val ids = AppNotificationChannel.entries.map { it.id }
    assertEquals(ids.size, ids.toSet().size)
  }

  @Test
  fun chatChannelIsDistinctFromExpiration() {
    assertEquals("chat", AppNotificationChannel.CHAT.id)
    assertNotEquals(AppNotificationChannel.EXPIRATION.id, AppNotificationChannel.CHAT.id)
    assertNotEquals(AppNotificationChannel.EXPIRATION.nameRes, AppNotificationChannel.CHAT.nameRes)
  }

  @Test
  fun chatChannelIsHighImportance() {
    assertEquals(NotificationManagerCompat.IMPORTANCE_HIGH, AppNotificationChannel.CHAT.importance)
  }

  @Test
  fun publishingChatCreatesOnlyTheChatChannel() {
    shadowOf(context).grantPermissions(NotificationPermission.PERMISSION)

    publisher.publish(chatNotification())

    val channel = manager.getNotificationChannel(AppNotificationChannel.CHAT.id)
    assertNotNull(channel)
    assertEquals("Household chat", channel.name.toString())
    assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    assertNull(manager.getNotificationChannel(AppNotificationChannel.EXPIRATION.id))
    assertEquals(
        AppNotificationChannel.CHAT.id, shadowOf(manager).allNotifications.single().channelId)
  }
}
