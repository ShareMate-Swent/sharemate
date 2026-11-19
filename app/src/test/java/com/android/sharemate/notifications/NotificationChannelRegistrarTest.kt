// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationChannelRegistrarTest {
  private val context = ApplicationProvider.getApplicationContext<Application>()
  private val systemManager = context.getSystemService(NotificationManager::class.java)
  private val registrar = NotificationChannelRegistrar.from(context)

  @Test
  fun registerAllCreatesEveryChannelWithItsNameDescriptionAndImportance() {
    registrar.registerAll()

    AppNotificationChannel.entries.forEach { channel ->
      val created = systemManager.getNotificationChannel(channel.id)
      assertNotNull("${channel.id} was not created", created)
      assertEquals(context.getString(channel.nameRes), created.name.toString())
      assertEquals(context.getString(channel.descriptionRes), created.description)
      assertEquals(channel.importance, created.importance)
    }
  }

  @Test
  fun chatChannelIsHighImportanceAndDistinctFromExpiration() {
    registrar.registerAll()

    val chat = systemManager.getNotificationChannel(AppNotificationChannel.CHAT.id)
    val expiration = systemManager.getNotificationChannel(AppNotificationChannel.EXPIRATION.id)
    assertEquals(NotificationManager.IMPORTANCE_HIGH, chat.importance)
    assertEquals(NotificationManager.IMPORTANCE_DEFAULT, expiration.importance)
  }

  @Test
  fun registeringTwiceKeepsOneChannelPerEntry() {
    registrar.registerAll()
    registrar.registerAll()

    assertEquals(AppNotificationChannel.entries.size, systemManager.notificationChannels.size)
  }

  @Test
  fun registeringKeepsTheMuteChosenByTheUser() {
    registrar.registerAll()
    val chat = systemManager.getNotificationChannel(AppNotificationChannel.CHAT.id)
    chat.importance = NotificationManager.IMPORTANCE_NONE
    systemManager.createNotificationChannel(chat)

    registrar.registerAll()

    val after = systemManager.getNotificationChannel(AppNotificationChannel.CHAT.id)
    assertEquals(NotificationManager.IMPORTANCE_NONE, after.importance)
  }

  @Test
  fun registerAllKeepsCreatingTheOtherChannelsWhenOneFails() {
    val manager = mock(NotificationManagerCompat::class.java)
    val failing = AppNotificationChannel.entries.first().id
    doAnswer { invocation ->
          if (invocation.getArgument<NotificationChannel>(0).id == failing) {
            throw IllegalStateException("boom")
          }
        }
        .`when`(manager)
        .createNotificationChannel(any(NotificationChannel::class.java))
    val flaky = NotificationChannelRegistrar(context, manager)

    flaky.registerAll()

    verify(manager, times(AppNotificationChannel.entries.size))
        .createNotificationChannel(any(NotificationChannel::class.java))
  }

  @Test
  fun registerLetsTheFailureReachThePublisher() {
    val manager = mock(NotificationManagerCompat::class.java)
    doThrow(IllegalStateException("boom"))
        .`when`(manager)
        .createNotificationChannel(any(NotificationChannel::class.java))
    val failing = NotificationChannelRegistrar(context, manager)

    assertThrows(IllegalStateException::class.java) {
      failing.register(AppNotificationChannel.CHAT)
    }
  }
}
