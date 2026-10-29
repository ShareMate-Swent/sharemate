// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.notifications.expiration

import com.android.sharemate.model.item.ExpirationDates
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemStatus
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpirationSummaryTest {
  private val today = LocalDate.of(2026, 10, 15)

  private fun item(
      name: String,
      offsetDays: Long?,
      status: ItemStatus = ItemStatus.ACTIVE,
  ) =
      Item(
          id = name,
          name = name,
          expirationDate = offsetDays?.let { ExpirationDates.toDate(today.plusDays(it)) },
          status = status)

  @Test
  fun splitsItemsByExpiryStatus() {
    val expired = item("expired", -1)
    val soon = item("soon", ExpirationDates.SOON_THRESHOLD_DAYS)
    val fresh = item("fresh", ExpirationDates.SOON_THRESHOLD_DAYS + 1)

    val summary = ExpirationSummary.of(listOf(fresh, soon, expired), today)

    assertEquals(listOf(expired), summary.expired)
    assertEquals(listOf(soon), summary.expiringSoon)
    assertFalse(summary.isEmpty)
  }

  @Test
  fun todayCountsAsExpiringSoon() {
    val summary = ExpirationSummary.of(listOf(item("milk", 0)), today)

    assertTrue(summary.expired.isEmpty())
    assertEquals(listOf("milk"), summary.expiringSoon.map { it.name })
  }

  @Test
  fun ignoresItemsWithoutExpirationDate() {
    assertTrue(ExpirationSummary.of(listOf(item("salt", null)), today).isEmpty)
  }

  @Test
  fun ignoresItemsAlreadyEatenOrDiscarded() {
    val items =
        listOf(
            item("eaten", -2, ItemStatus.EATEN),
            item("thrown", 1, ItemStatus.DISCARDED),
        )

    assertTrue(ExpirationSummary.of(items, today).isEmpty)
  }

  @Test
  fun emptyInventoryHasNothingToReport() {
    assertTrue(ExpirationSummary.of(emptyList(), today).isEmpty)
  }
}
