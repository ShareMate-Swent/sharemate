// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications.expiration

import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.user.ExpirationPreferencesProvider
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
 * opens the fridge. If the user's preferences cannot be read, the reminders still go out, with the
 * default preferences.
 */
class ExpirationNotificationSource(
    private val itemRepository: ItemRepository,
    private val recipientProvider: NotificationRecipientProvider,
    private val preferencesProvider: ExpirationPreferencesProvider,
    private val formatter: ExpirationNotificationFormatter,
    private val today: () -> LocalDate = { LocalDate.now() },
) : NotificationSource {

  override suspend fun dueNotifications(): List<AppNotification> {
    val recipient = recipientProvider.current() ?: return emptyList()
    val items = itemRepository.getFridgeItems(recipient.householdId, recipient.userId).first()
    val preferences = preferencesProvider.get(recipient.userId)
    val summary = ExpirationSummary.of(items, today(), preferences)
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
