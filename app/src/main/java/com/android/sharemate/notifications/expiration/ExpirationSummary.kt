// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications.expiration

import com.android.sharemate.model.item.ExpirationDates
import com.android.sharemate.model.item.ExpiryStatus
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemStatus
import com.android.sharemate.model.user.ExpirationPreferences
import java.time.LocalDate

/** The active items to remind the user about today, split by how urgent they are. */
data class ExpirationSummary(val expired: List<Item>, val expiringSoon: List<Item>) {
  val isEmpty: Boolean
    get() = expired.isEmpty() && expiringSoon.isEmpty()

  companion object {
    /**
     * Keeps the items [preferences] say are due for a reminder on [today]. Items already eaten or
     * thrown away, and items without an expiration date, never need attention.
     *
     * Which list an item belongs to is decided by [ExpirationDates.statusOfDaysLeft], the rule the
     * fridge cards use too. Expiring soon (1 to `soonDays` days left): a reminder on the first day
     * of that period, the day before expiry, and every `soonRepeatDays` days in between. Expired
     * (the expiry day and after): a reminder on the expiry day, then every `expiredRepeatDays`
     * days.
     */
    fun of(
        items: List<Item>,
        today: LocalDate,
        preferences: ExpirationPreferences,
    ): ExpirationSummary {
      val expired = mutableListOf<Item>()
      val expiringSoon = mutableListOf<Item>()
      for (item in items) {
        if (item.status != ItemStatus.ACTIVE) continue
        when (reminderOf(item, today, preferences)) {
          ExpiryStatus.EXPIRED -> expired += item
          ExpiryStatus.SOON -> expiringSoon += item
          ExpiryStatus.NORMAL -> Unit
        }
      }
      return ExpirationSummary(expired, expiringSoon)
    }

    /** The list [item] belongs to today, or [ExpiryStatus.NORMAL] when it is not due. */
    private fun reminderOf(
        item: Item,
        today: LocalDate,
        preferences: ExpirationPreferences,
    ): ExpiryStatus {
      val daysLeft =
          ExpirationDates.daysUntil(item.expirationDate, today) ?: return ExpiryStatus.NORMAL
      val status = ExpirationDates.statusOfDaysLeft(daysLeft, preferences.soonDays.toLong())
      val due =
          when (status) {
            ExpiryStatus.EXPIRED -> isExpiredReminderDay(-daysLeft, preferences)
            ExpiryStatus.SOON -> isSoonReminderDay(daysLeft, preferences)
            ExpiryStatus.NORMAL -> false
          }
      return if (due) status else ExpiryStatus.NORMAL
    }

    private fun isSoonReminderDay(daysLeft: Long, preferences: ExpirationPreferences): Boolean {
      val soonDays = preferences.soonDays.toLong()
      val repeat = preferences.soonRepeatDays?.toLong()
      return daysLeft == soonDays ||
          daysLeft == ExpirationDates.LAST_SOON_DAY ||
          (repeat != null && (soonDays - daysLeft) % repeat == 0L)
    }

    private fun isExpiredReminderDay(daysSince: Long, preferences: ExpirationPreferences): Boolean {
      val repeat = preferences.expiredRepeatDays?.toLong()
      return daysSince == ExpirationDates.EXPIRY_DAY || (repeat != null && daysSince % repeat == 0L)
    }
  }
}
