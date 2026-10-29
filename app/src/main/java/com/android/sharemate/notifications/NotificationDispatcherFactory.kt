// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.notifications

import android.content.Context
import com.android.sharemate.MainActivity

/**
 * Wires the production notification pipeline. The list of sources is the one place to extend: add a
 * [NotificationSource] for each feature that can notify (expirations, receipts, recipes, ...).
 */
object NotificationDispatcherFactory {
  fun create(context: Context): NotificationDispatcher {
    val sources = emptyList<NotificationSource>()
    return NotificationDispatcher(
        sources, AndroidNotificationPublisher(context, MainActivity::class.java))
  }
}
