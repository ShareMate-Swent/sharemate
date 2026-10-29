// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.notifications

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.android.sharemate.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AndroidNotificationPublisherTest {
  private val context = ApplicationProvider.getApplicationContext<Application>()
  private val manager = context.getSystemService(NotificationManager::class.java)
  private val publisher = AndroidNotificationPublisher(context, MainActivity::class.java)

  private fun notification(key: String = "expiration", text: String = "2 items expired.") =
      AppNotification(
          key = key,
          channel = AppNotificationChannel.EXPIRATION,
          title = "Check your fridge",
          text = text,
          deepLink = "sharemate://app/fridge")

  @Test
  fun postsOnItsChannelWithTheDeepLinkAsTapTarget() {
    shadowOf(context).grantPermissions(NotificationPermission.PERMISSION)

    publisher.publish(notification())

    val posted = shadowOf(manager).allNotifications.single()
    assertEquals("Check your fridge", posted.extras.getString("android.title"))
    assertEquals(AppNotificationChannel.EXPIRATION.id, posted.channelId)
    assertNotNull(manager.getNotificationChannel(AppNotificationChannel.EXPIRATION.id))
    val tapIntent: Intent = shadowOf(posted.contentIntent).savedIntent
    assertEquals(Intent.ACTION_VIEW, tapIntent.action)
    assertEquals("sharemate://app/fridge", tapIntent.data.toString())
    assertEquals(MainActivity::class.java.name, tapIntent.component?.className)
  }

  @Test
  fun republishingTheSameKeyReplacesTheNotification() {
    shadowOf(context).grantPermissions(NotificationPermission.PERMISSION)

    publisher.publish(notification(text = "first"))
    publisher.publish(notification(text = "second"))

    val posted = shadowOf(manager).allNotifications.single()
    assertEquals("second", posted.extras.getString("android.text"))
  }

  @Test
  fun doesNotPostWithoutThePermission() {
    shadowOf(context).denyPermissions(NotificationPermission.PERMISSION)

    publisher.publish(notification())

    assertTrue(shadowOf(manager).allNotifications.isEmpty())
  }
}
