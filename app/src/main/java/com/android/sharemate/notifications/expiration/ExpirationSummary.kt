// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.notifications.expiration

import com.android.sharemate.model.item.ExpirationDates
import com.android.sharemate.model.item.ExpiryStatus
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemStatus
import java.time.LocalDate

/** The active items that need attention, split by how urgent they are. */
data class ExpirationSummary(val expired: List<Item>, val expiringSoon: List<Item>) {
  val isEmpty: Boolean
    get() = expired.isEmpty() && expiringSoon.isEmpty()

  companion object {
    /**
     * Classifies [items] with [ExpirationDates.status]. Items already eaten or thrown away, and
     * items without an expiration date, never need attention.
     */
    fun of(items: List<Item>, today: LocalDate): ExpirationSummary {
      val statuses =
          items
              .filter { it.status == ItemStatus.ACTIVE }
              .groupBy { ExpirationDates.status(it.expirationDate, today) }
      return ExpirationSummary(
          expired = statuses[ExpiryStatus.EXPIRED].orEmpty(),
          expiringSoon = statuses[ExpiryStatus.SOON].orEmpty())
    }
  }
}
