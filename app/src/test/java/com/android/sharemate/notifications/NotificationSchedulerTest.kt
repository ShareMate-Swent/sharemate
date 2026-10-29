// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.notifications

import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationSchedulerTest {
  private val zone = ZoneId.of("Europe/Zurich")

  private fun at(day: Int, hour: Int, minute: Int = 0) =
      ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone)

  @Test
  fun waitsForTheSameDayWhenBeforeTheCheckHour() {
    assertEquals(Duration.ofHours(2), NotificationScheduler.initialDelay(at(15, 7)))
  }

  @Test
  fun waitsForTomorrowWhenTheCheckHourHasPassed() {
    assertEquals(Duration.ofHours(23), NotificationScheduler.initialDelay(at(15, 10)))
  }

  @Test
  fun waitsAFullDayAtTheCheckHourItself() {
    assertEquals(Duration.ofHours(24), NotificationScheduler.initialDelay(at(15, 9)))
  }

  @Test
  fun followsTheWallClockAcrossDaylightSavingChanges() {
    // Clocks go back on 25 October 2026: the night lasts one hour longer.
    assertEquals(Duration.ofHours(10), NotificationScheduler.initialDelay(at(25, 0)))
  }
}
