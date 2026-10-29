// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.notifications.expiration

import android.content.res.Resources
import com.android.sharemate.R

/** Title and body of a notification, already localized. */
data class NotificationText(val title: String, val text: String)

/** Turns a non-empty [ExpirationSummary] into the text shown to the user. */
fun interface ExpirationNotificationFormatter {
  fun format(summary: ExpirationSummary): NotificationText
}

class ResourceExpirationNotificationFormatter(private val resources: Resources) :
    ExpirationNotificationFormatter {
  override fun format(summary: ExpirationSummary): NotificationText {
    val parts = buildList {
      if (summary.expired.isNotEmpty()) {
        val count = summary.expired.size
        add(resources.getQuantityString(R.plurals.notification_expiration_expired, count, count))
      }
      if (summary.expiringSoon.isNotEmpty()) {
        val count = summary.expiringSoon.size
        add(resources.getQuantityString(R.plurals.notification_expiration_soon, count, count))
      }
    }
    return NotificationText(
        title = resources.getString(R.string.notification_expiration_title),
        text = parts.joinToString(separator = ". ", postfix = "."))
  }
}
