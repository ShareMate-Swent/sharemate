// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications.expiration

import com.android.sharemate.model.item.ExpirationDates
import com.android.sharemate.model.item.ExpiryStatus
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemStatus
import com.android.sharemate.model.user.ExpirationPreferences
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpirationSummaryTest {
  private val today = LocalDate.of(2026, 10, 15)
  private val defaults = ExpirationPreferences()

  private fun item(
      name: String,
      offsetDays: Long?,
      status: ItemStatus = ItemStatus.ACTIVE,
  ) =
      Item(
          id = name,
          name = name,
          expirationDate = offsetDays?.let { ExpirationDates.toDate(today.plusDays(it)) },
          status = status,
          householdId = "household-1")

  @Test
  fun splitsItemsByExpiryStatus() {
    val expired = item("expired", -1)
    val soon = item("soon", ExpirationDates.SOON_THRESHOLD_DAYS)
    val fresh = item("fresh", ExpirationDates.SOON_THRESHOLD_DAYS + 1)

    val summary = ExpirationSummary.of(listOf(fresh, soon, expired), today, defaults)

    assertEquals(listOf(expired), summary.expired)
    assertEquals(listOf(soon), summary.expiringSoon)
    assertFalse(summary.isEmpty)
  }

  @Test
  fun expiryDayIsAnExpiredReminder() {
    val summary = ExpirationSummary.of(listOf(item("milk", 0)), today, defaults)

    assertTrue(summary.expiringSoon.isEmpty())
    assertEquals(listOf("milk"), summary.expired.map { it.name })
  }

  private fun due(offset: Long, preferences: ExpirationPreferences): ExpirationSummary =
      ExpirationSummary.of(listOf(item("x", offset)), today, preferences)

  @Test
  fun soonPeriodRemindsOnFirstDayRepeatsAndDayBefore() {
    val prefs = ExpirationPreferences(soonDays = 10, soonRepeatDays = 4, expiredRepeatDays = 1)

    // 10 = first day, 6 = repeat, 2 = repeat, 1 = day before expiry.
    val reminded = (1L..10L).filter { due(it, prefs).expiringSoon.isNotEmpty() }

    assertEquals(listOf(1L, 2L, 6L, 10L), reminded)
  }

  @Test
  fun noRepeatWhileSoonStillRemindsFirstAndLastDay() {
    val prefs = ExpirationPreferences(soonDays = 5, soonRepeatDays = null)

    assertEquals(listOf(1L, 5L), (1L..5L).filter { due(it, prefs).expiringSoon.isNotEmpty() })
  }

  @Test
  fun itemsOutsideTheSoonWindowAreNotReminded() {
    val prefs = ExpirationPreferences(soonDays = 5)

    assertTrue(due(6, prefs).isEmpty)
  }

  @Test
  fun expiredItemsAreRemindedOnExpiryDayThenEveryYDays() {
    val prefs = ExpirationPreferences(expiredRepeatDays = 3)

    val reminded = (0L..9L).filter { due(-it, prefs).expired.isNotEmpty() }

    assertEquals(listOf(0L, 3L, 6L, 9L), reminded)
  }

  @Test
  fun noRepeatOnceExpiredOnlyRemindsOnExpiryDay() {
    val prefs = ExpirationPreferences(expiredRepeatDays = null)

    assertEquals(listOf(0L), (0L..9L).filter { due(-it, prefs).expired.isNotEmpty() })
  }

  @Test
  fun soonAndExpiredRepeatsAreIndependent() {
    val prefs = ExpirationPreferences(soonDays = 6, soonRepeatDays = 2, expiredRepeatDays = 5)

    assertEquals(
        listOf(1L, 2L, 4L, 6L), (1L..6L).filter { due(it, prefs).expiringSoon.isNotEmpty() })
    assertEquals(listOf(0L, 5L), (0L..9L).filter { due(-it, prefs).expired.isNotEmpty() })
  }

  @Test
  fun everyDayAroundTheExpiryDayIsClassifiedLikeTheFridgeCards() {
    val soonDaysToCheck =
        listOf(
            ExpirationPreferences.SOON_DAYS_RANGE.first,
            ExpirationPreferences.DEFAULT_SOON_DAYS,
            ExpirationPreferences.SOON_DAYS_RANGE.last)
    for (soonDays in soonDaysToCheck) {
      // Reminders every day, so an item is listed exactly when the cards flag it.
      val prefs =
          ExpirationPreferences(soonDays = soonDays, soonRepeatDays = 1, expiredRepeatDays = 1)
      for (daysLeft in -3L..(soonDays + 3L)) {
        val summary = due(daysLeft, prefs)
        val status = ExpirationDates.statusOfDaysLeft(daysLeft, soonDays.toLong())

        assertEquals(
            "expired, $daysLeft of $soonDays",
            status == ExpiryStatus.EXPIRED,
            summary.expired.isNotEmpty())
        assertEquals(
            "soon, $daysLeft of $soonDays",
            status == ExpiryStatus.SOON,
            summary.expiringSoon.isNotEmpty())
      }
    }
  }

  @Test
  fun theDayBeforeExpiryIsSoonAndTheExpiryDayIsExpired() {
    assertEquals(1, due(1, defaults).expiringSoon.size)
    assertTrue(due(1, defaults).expired.isEmpty())
    assertEquals(1, due(0, defaults).expired.size)
    assertTrue(due(0, defaults).expiringSoon.isEmpty())
  }

  @Test
  fun windowBoundariesOfTheSoonRangeAreRespected() {
    val narrow = ExpirationPreferences(soonDays = ExpirationPreferences.SOON_DAYS_RANGE.first)
    val wide = ExpirationPreferences(soonDays = ExpirationPreferences.SOON_DAYS_RANGE.last)

    assertEquals(1, due(1, narrow).expiringSoon.size)
    assertTrue(due(2, narrow).isEmpty)
    assertEquals(1, due(60, wide).expiringSoon.size)
    assertTrue(due(61, wide).isEmpty)
  }

  @Test
  fun largestRepeatsOnlyRemindOnTheirOwnDay() {
    val prefs =
        ExpirationPreferences(
            soonDays = 60,
            soonRepeatDays = ExpirationPreferences.REPEAT_DAYS_RANGE.last,
            expiredRepeatDays = ExpirationPreferences.REPEAT_DAYS_RANGE.last)

    assertEquals(
        listOf(1L, 30L, 60L), (1L..60L).filter { due(it, prefs).expiringSoon.isNotEmpty() })
    assertEquals(listOf(0L, 30L, 60L), (0L..60L).filter { due(-it, prefs).expired.isNotEmpty() })
  }

  @Test
  fun ignoresItemsWithoutExpirationDate() {
    assertTrue(ExpirationSummary.of(listOf(item("salt", null)), today, defaults).isEmpty)
  }

  @Test
  fun ignoresItemsAlreadyEatenOrDiscarded() {
    val items =
        listOf(
            item("eaten", -2, ItemStatus.EATEN),
            item("thrown", 1, ItemStatus.DISCARDED),
        )

    assertTrue(ExpirationSummary.of(items, today, defaults).isEmpty)
  }

  @Test
  fun emptyInventoryHasNothingToReport() {
    assertTrue(ExpirationSummary.of(emptyList(), today, defaults).isEmpty)
  }
}
