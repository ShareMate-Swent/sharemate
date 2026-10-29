// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.notifications

import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import com.android.sharemate.R

/**
 * A notification category. Users can mute each channel independently in the system settings, so
 * every new kind of notification (receipts, recipes, ...) gets its own entry here.
 */
enum class AppNotificationChannel(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val descriptionRes: Int,
    val importance: Int = NotificationManagerCompat.IMPORTANCE_DEFAULT,
) {
  EXPIRATION(
      id = "expiration",
      nameRes = R.string.notification_channel_expiration_name,
      descriptionRes = R.string.notification_channel_expiration_description),
}

/**
 * A notification the app wants to show, free of any Android API so that it can be produced and
 * tested on the JVM.
 *
 * @property key Stable identity within the app. Publishing a notification with the same key
 *   replaces the previous one instead of stacking a new one.
 * @property deepLink URI opened when the user taps the notification; see
 *   [com.android.sharemate.ui.navigation.AppDeepLinks].
 */
data class AppNotification(
    val key: String,
    val channel: AppNotificationChannel,
    val title: String,
    val text: String,
    val deepLink: String,
)
