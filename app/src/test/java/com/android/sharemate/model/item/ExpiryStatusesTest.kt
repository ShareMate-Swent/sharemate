// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.item

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ExpiryStatusesTest {
  private val today = LocalDate.of(2026, 10, 15)

  private fun item(id: String, daysLeft: Long?) =
      Item(
          id = id,
          name = id,
          expirationDate = daysLeft?.let { ExpirationDates.toDate(today.plusDays(it)) },
          householdId = "household-1")

  @Test
  fun everyItemGetsItsStatusByIdWithTheGivenWindow() {
    val items =
        listOf(item("old", -2), item("today", 0), item("next", 1), item("edge", 3), item("far", 4))

    val statuses = ExpiryStatuses.of(items, today, soonThresholdDays = 3)

    assertEquals(
        mapOf(
            "old" to ExpiryStatus.EXPIRED,
            "today" to ExpiryStatus.EXPIRED,
            "next" to ExpiryStatus.SOON,
            "edge" to ExpiryStatus.SOON,
            "far" to ExpiryStatus.NORMAL),
        statuses)
  }

  @Test
  fun itemsWithoutAnExpirationDateAreNormal() {
    assertEquals(
        mapOf("salt" to ExpiryStatus.NORMAL),
        ExpiryStatuses.of(listOf(item("salt", null)), today, soonThresholdDays = 3))
  }

  @Test
  fun aWiderWindowFlagsMoreItems() {
    val items = listOf(item("far", 10))

    assertEquals(ExpiryStatus.NORMAL, ExpiryStatuses.of(items, today, 9).getValue("far"))
    assertEquals(ExpiryStatus.SOON, ExpiryStatuses.of(items, today, 10).getValue("far"))
  }

  @Test
  fun noItemsGivesNoStatuses() {
    assertEquals(emptyMap<String, ExpiryStatus>(), ExpiryStatuses.of(emptyList(), today, 3))
  }
}
