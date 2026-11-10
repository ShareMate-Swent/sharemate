// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.item

import java.time.LocalDate

/** Works out the [ExpiryStatus] of many items at once, for the screens that only display it. */
object ExpiryStatuses {
  /**
   * The [ExpiryStatus] of each of [items] seen from [today], by item id, using the single rule of
   * [ExpirationDates.status] with the user's [soonThresholdDays] window. Items without an
   * expiration date are [ExpiryStatus.NORMAL].
   */
  fun of(items: List<Item>, today: LocalDate, soonThresholdDays: Long): Map<String, ExpiryStatus> =
      items.associate {
        it.id to ExpirationDates.status(it.expirationDate, today, soonThresholdDays)
      }
}
