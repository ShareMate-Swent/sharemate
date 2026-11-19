// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * Creates the system notification channels described by [AppNotificationChannel].
 *
 * A channel must exist before a notification targets it, and only an existing channel shows up in
 * the system settings where the user can mute it. Creating a channel again is harmless: Android
 * keeps the existing one, so the user's own choices (mute, importance) are never reset.
 */
class NotificationChannelRegistrar(
    private val context: Context,
    private val manager: NotificationManagerCompat,
) {
  /**
   * Creates [channel]. Lets any failure propagate so that a caller which publishes a notification
   * knows it cannot do so.
   */
  @SuppressLint("WrongConstant") // The importance is always one of the NotificationManager values.
  fun register(channel: AppNotificationChannel) {
    val systemChannel =
        NotificationChannel(channel.id, context.getString(channel.nameRes), channel.importance)
            .apply { description = context.getString(channel.descriptionRes) }
    manager.createNotificationChannel(systemChannel)
  }

  /**
   * Creates every channel at app start. A channel that cannot be created is logged and skipped
   * without stopping the others or the app: publishing a notification creates its channel again.
   */
  fun registerAll() {
    AppNotificationChannel.entries.forEach { channel ->
      try {
        register(channel)
      } catch (error: RuntimeException) {
        Log.e(TAG, "Unable to create the notification channel ${channel.id}", error)
      }
    }
  }

  /** Builds the registrar used by the app itself. */
  companion object {
    /** Log tag of the channel creation failures. */
    const val TAG = "NotificationChannels"

    /** Wires a registrar on the system notification manager of [context]. */
    fun from(context: Context): NotificationChannelRegistrar =
        NotificationChannelRegistrar(context, NotificationManagerCompat.from(context))
  }
}
