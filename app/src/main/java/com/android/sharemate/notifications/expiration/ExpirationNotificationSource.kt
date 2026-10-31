// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.notifications.expiration

import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.notifications.AppNotification
import com.android.sharemate.notifications.AppNotificationChannel
import com.android.sharemate.notifications.NotificationRecipientProvider
import com.android.sharemate.notifications.NotificationSource
import com.android.sharemate.ui.navigation.AppDeepLinks
import com.android.sharemate.ui.navigation.TopLevelDestination
import java.time.LocalDate
import kotlinx.coroutines.flow.first

/**
 * Reminds the household about food that has expired or is about to, with a single notification that
 * opens the fridge.
 */
class ExpirationNotificationSource(
    private val itemRepository: ItemRepository,
    private val recipientProvider: NotificationRecipientProvider,
    private val formatter: ExpirationNotificationFormatter,
    private val today: () -> LocalDate = { LocalDate.now() },
) : NotificationSource {

  override suspend fun dueNotifications(): List<AppNotification> {
    val recipient = recipientProvider.current() ?: return emptyList()
    val items = itemRepository.getFridgeItems(recipient.userId, recipient.householdId).first()
    val summary = ExpirationSummary.of(items, today())
    if (summary.isEmpty) return emptyList()
    val text = formatter.format(summary)
    return listOf(
        AppNotification(
            key = KEY,
            channel = AppNotificationChannel.EXPIRATION,
            title = text.title,
            text = text.text,
            deepLink = AppDeepLinks.uri(TopLevelDestination.FRIDGE)))
  }

  companion object {
    const val KEY = "expiration"
  }
}
