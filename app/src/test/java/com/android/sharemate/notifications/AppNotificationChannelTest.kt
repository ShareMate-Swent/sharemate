// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications

import androidx.core.app.NotificationManagerCompat
import com.android.sharemate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AppNotificationChannelTest {
  @Test
  fun chatChannelIsDeclaredWithItsOwnIdTextsAndHighImportance() {
    val chat = AppNotificationChannel.CHAT

    assertEquals("chat", chat.id)
    assertEquals(R.string.notification_channel_chat_name, chat.nameRes)
    assertEquals(R.string.notification_channel_chat_description, chat.descriptionRes)
    assertEquals(NotificationManagerCompat.IMPORTANCE_HIGH, chat.importance)
  }

  @Test
  fun expirationChannelIsUnchanged() {
    val expiration = AppNotificationChannel.EXPIRATION

    assertEquals("expiration", expiration.id)
    assertEquals(NotificationManagerCompat.IMPORTANCE_DEFAULT, expiration.importance)
  }

  @Test
  fun everyChannelHasItsOwnIdSoItCanBeMutedSeparately() {
    val ids = AppNotificationChannel.entries.map { it.id }

    assertEquals(ids.size, ids.toSet().size)
    assertNotEquals(AppNotificationChannel.EXPIRATION.id, AppNotificationChannel.CHAT.id)
  }
}
