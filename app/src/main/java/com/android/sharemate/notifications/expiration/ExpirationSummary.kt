// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.notifications.expiration

import com.android.sharemate.model.item.ExpirationDates
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
     * Expiring soon (1 to `soonDays` days left): a reminder on the first day of that period, the
     * day before expiry, and every `soonRepeatDays` days in between. Expired (the expiry day and
     * after): a reminder on the expiry day, then every `expiredRepeatDays` days.
     */
    fun of(
        items: List<Item>,
        today: LocalDate,
        preferences: ExpirationPreferences = ExpirationPreferences(),
    ): ExpirationSummary {
      val due = mutableListOf<Pair<Item, Long>>()
      for (item in items) {
        if (item.status != ItemStatus.ACTIVE) continue
        val days = ExpirationDates.daysUntil(item.expirationDate, today) ?: continue
        due += item to days
      }
      return ExpirationSummary(
          expired =
              due.filter { (_, d) -> d <= EXPIRY_DAY && isExpiredReminderDay(-d, preferences) }
                  .map { it.first },
          expiringSoon =
              due.filter { (_, d) ->
                    d in LAST_SOON_DAY..preferences.soonDays && isSoonReminderDay(d, preferences)
                  }
                  .map { it.first })
    }

    private const val EXPIRY_DAY = 0L
    private const val LAST_SOON_DAY = 1L

    private fun isSoonReminderDay(daysLeft: Long, preferences: ExpirationPreferences): Boolean {
      val soonDays = preferences.soonDays.toLong()
      val repeat = preferences.soonRepeatDays?.toLong()
      return daysLeft == soonDays ||
          daysLeft == LAST_SOON_DAY ||
          (repeat != null && (soonDays - daysLeft) % repeat == 0L)
    }

    private fun isExpiredReminderDay(daysSince: Long, preferences: ExpirationPreferences): Boolean {
      val repeat = preferences.expiredRepeatDays?.toLong()
      return daysSince == EXPIRY_DAY || (repeat != null && daysSince % repeat == 0L)
    }
  }
}
