// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate

import android.app.NotificationManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.android.sharemate.notifications.AppNotificationChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Checks that launching the real activity creates every notification channel. */
@RunWith(RobolectricTestRunner::class)
class MainActivityNotificationChannelsTest {
  @get:Rule(order = 0) val firebase = FirebaseAuthTestRule()
  @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

  private val manager: NotificationManager
    get() = compose.activity.getSystemService(NotificationManager::class.java)

  @Test
  fun launchingTheAppCreatesEveryChannelBeforeAnyNotificationIsPublished() {
    AppNotificationChannel.entries.forEach { channel ->
      val created = manager.getNotificationChannel(channel.id)
      assertNotNull("${channel.id} was not created at start-up", created)
      assertEquals(channel.importance, created.importance)
    }
  }

  @Test
  fun launchingTheAppCreatesTheChatChannelSeparatelyFromExpiration() {
    assertNotNull(manager.getNotificationChannel(AppNotificationChannel.CHAT.id))
    assertNotNull(manager.getNotificationChannel(AppNotificationChannel.EXPIRATION.id))
  }
}
