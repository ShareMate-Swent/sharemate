// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert
import org.junit.Test

class ExpirationDatesTest {
  private val originalZone = TimeZone.getDefault()
  private val today = LocalDate.of(2026, 10, 15)

  @After
  fun restoreTimeZone() {
    TimeZone.setDefault(originalZone)
  }

  @Test
  fun blankInputMeansNoExpirationDate() {
    listOf("", " ", " \t ").forEach {
      Assert.assertEquals(ExpirationInput.Empty, ExpirationDates.parse(it))
    }
  }

  @Test
  fun validInputIsTrimmedAndStoredAsMidnightUtc() {
    val expected = ExpirationInput.Valid(ExpirationDates.toDate(LocalDate.of(2026, 10, 12)))
    Assert.assertEquals(expected, ExpirationDates.parse("2026-10-12"))
    Assert.assertEquals(expected, ExpirationDates.parse("  2026-10-12  "))
    Assert.assertEquals(
        86_400_000L, (ExpirationDates.parse("1970-01-02") as ExpirationInput.Valid).date.time)
  }

  @Test
  fun leapDayIsAcceptedOnlyInLeapYears() {
    Assert.assertEquals(
        ExpirationInput.Valid(ExpirationDates.toDate(LocalDate.of(2024, 2, 29))),
        ExpirationDates.parse("2024-02-29"))
    Assert.assertEquals(ExpirationInput.Invalid, ExpirationDates.parse("2025-02-29"))
  }

  @Test
  fun malformedOrImpossibleInputIsInvalid() {
    listOf("2026-02-30", "2026-13-01", "2026-2-01", "12/10/2026", "not a date", "2026-10-12T00:00")
        .forEach { Assert.assertEquals(it, ExpirationInput.Invalid, ExpirationDates.parse(it)) }
  }

  @Test
  fun formatInputIsTheInverseOfParse() {
    Assert.assertEquals("", ExpirationDates.formatInput(null))
    val day = LocalDate.of(2026, 12, 31)
    Assert.assertEquals("2026-12-31", ExpirationDates.formatInput(ExpirationDates.toDate(day)))
    Assert.assertEquals(
        ExpirationInput.Valid(ExpirationDates.toDate(day)),
        ExpirationDates.parse(ExpirationDates.formatInput(ExpirationDates.toDate(day))))
  }

  @Test
  fun statusUsesTheThresholdAndTheGivenToday() {
    fun status(offset: Long) =
        ExpirationDates.status(ExpirationDates.toDate(today.plusDays(offset)), today)

    Assert.assertEquals(ExpiryStatus.NORMAL, ExpirationDates.status(null, today))
    Assert.assertEquals(ExpiryStatus.EXPIRED, status(-1))
    Assert.assertEquals(ExpiryStatus.SOON, status(0))
    Assert.assertEquals(ExpiryStatus.SOON, status(ExpirationDates.SOON_THRESHOLD_DAYS))
    Assert.assertEquals(ExpiryStatus.NORMAL, status(ExpirationDates.SOON_THRESHOLD_DAYS + 1))
  }

  /** Non-regression: the stored day must never shift with the device's time zone. */
  @Test
  fun storedDayIsTheSameInEveryTimeZone() {
    val day = LocalDate.of(2026, 10, 16)
    listOf("UTC", "America/New_York", "Pacific/Honolulu", "Europe/Zurich", "Pacific/Kiritimati")
        .forEach { zone ->
          TimeZone.setDefault(TimeZone.getTimeZone(zone))
          val date = ExpirationDates.toDate(day)
          Assert.assertEquals(zone, day, ExpirationDates.toLocalDate(date))
          Assert.assertEquals(zone, "2026-10-16", ExpirationDates.formatInput(date))
          Assert.assertEquals(zone, "16/10/2026", ExpirationDates.formatCompact(date, Locale.US))
          Assert.assertEquals(zone, "Oct 16, 2026", ExpirationDates.formatDisplay(date, Locale.US))
          Assert.assertEquals(
              zone, ExpirationInput.Valid(date), ExpirationDates.parse("2026-10-16"))
        }
  }
}
