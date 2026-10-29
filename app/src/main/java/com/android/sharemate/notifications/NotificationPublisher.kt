// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.android.sharemate.R

/** Shows notifications to the user. */
interface NotificationPublisher {
  /** Shows [notification], replacing any earlier one with the same key. */
  fun publish(notification: AppNotification)
}

/**
 * Publishes through the system notification manager. Tapping a notification opens [entryActivity]
 * on the notification's deep link.
 */
class AndroidNotificationPublisher(
    private val context: Context,
    private val entryActivity: Class<*>,
) : NotificationPublisher {
  private val manager = NotificationManagerCompat.from(context)

  @SuppressLint("MissingPermission") // Checked by canPost().
  override fun publish(notification: AppNotification) {
    if (!canPost()) return
    ensureChannel(notification.channel)
    manager.notify(notification.key, NOTIFICATION_ID, build(notification))
  }

  private fun canPost(): Boolean {
    val permitted =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    return permitted && manager.areNotificationsEnabled()
  }

  @SuppressLint("WrongConstant") // The importance is always one of the NotificationManager values.
  private fun ensureChannel(channel: AppNotificationChannel) {
    val systemChannel =
        NotificationChannel(channel.id, context.getString(channel.nameRes), channel.importance)
            .apply { description = context.getString(channel.descriptionRes) }
    manager.createNotificationChannel(systemChannel)
  }

  private fun build(notification: AppNotification) =
      NotificationCompat.Builder(context, notification.channel.id)
          .setSmallIcon(R.drawable.ic_notification)
          .setContentTitle(notification.title)
          .setContentText(notification.text)
          .setStyle(NotificationCompat.BigTextStyle().bigText(notification.text))
          .setContentIntent(contentIntent(notification))
          .setAutoCancel(true)
          .build()

  // SINGLE_TOP and CLEAR_TOP deliver a tap to the running activity via onNewIntent.
  private fun contentIntent(notification: AppNotification): PendingIntent {
    val intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(notification.deepLink), context, entryActivity)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    return PendingIntent.getActivity(
        context,
        notification.key.hashCode(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }

  private companion object {
    // Identity comes from the tag (the notification key); the numeric id is unused.
    const val NOTIFICATION_ID = 0
  }
}
