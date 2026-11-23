// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpiryDateParserTest {
  private val october12 = LocalDate.of(2026, 10, 12)

  @Test
  fun recognizesAllUserStoryExamples() {
    listOf("12/10/26", "12.10.2026", "12 OCT 2026").forEach { assertDate(it, october12) }
  }

  @Test
  fun recognizesNumericSeparatorsAndYearLengths() {
    listOf("/", ".", "-").forEach { separator ->
      listOf("26", "2026").forEach { year ->
        assertDate("12${separator}10$separator$year", october12)
      }
    }
  }

  @Test
  fun recognizesSingleDigitDaysAndMonths() {
    listOf("1/2/26", "01.2.2026", "1-02-2026", "1 FEB 26").forEach {
      assertDate(it, LocalDate.of(2026, 2, 1))
    }
  }

  @Test
  fun recognizesIsoCalendarDates() {
    assertDate("Best before: 2026-10-12", october12)
    assertDate("2024-02-29", LocalDate.of(2024, 2, 29))
  }

  @Test
  fun recognizesEveryEnglishMonthNameAndAbbreviation() {
    val names =
        listOf(
            listOf("JAN", "January"),
            listOf("FEB", "February"),
            listOf("MAR", "March"),
            listOf("APR", "April"),
            listOf("MAY", "May"),
            listOf("JUN", "June"),
            listOf("JUL", "July"),
            listOf("AUG", "August"),
            listOf("SEP", "SEPT", "September"),
            listOf("OCT", "October"),
            listOf("NOV", "November"),
            listOf("DEC", "December"))
    names.forEachIndexed { index, variants ->
      variants.forEach { name -> assertDate("12 $name 2026", LocalDate.of(2026, index + 1, 12)) }
    }
    assertDate("12 oCt. 26", october12)
  }

  @Test
  fun acceptsHorizontalWhitespaceAroundNumericSeparatorsAndBetweenWords() {
    listOf("12 / 10 / 26", "12\t.\t10\t.\t2026", "12\u00a0OCT\u00a02026", "12  OCT  2026").forEach {
      assertDate(it, october12)
    }
  }

  @Test
  fun extractsDatesFromLabelsAndSentencePunctuation() {
    listOf(
            "EXP: 12/10/26.",
            "Best before (12.10.2026)",
            "USE BY: [12 OCT 2026], keep refrigerated!",
            "\nExpiry:\n2026-10-12\r\nBatch 42")
        .forEach { assertDate(it, october12) }
  }

  @Test
  fun returnsDistinctDatesInTextOrderRatherThanChronologicalOrder() {
    assertEquals(
        listOf(october12, LocalDate.of(2026, 1, 1)),
        ExpiryDateParser.findDates(
            "EXP 12/10/26\nMFG 01 JAN 2026\n12.10.2026\n2026-01-01\n12 OCT 26"))
  }

  @Test
  fun keepsValidCandidatesAroundInvalidDates() {
    assertEquals(
        listOf(LocalDate.of(2026, 1, 1), october12),
        ExpiryDateParser.findDates("01/01/26, 31/02/26, 12 OCT 2026, 12 FOO 2026"))
  }

  @Test
  fun preservesNamedDatesSeparatedByADash() {
    assertEquals(
        listOf(LocalDate.of(2026, 1, 1), october12),
        ExpiryDateParser.findDates("MFG/EXP: 01 JAN 2026 - 12 OCT 2026"))
  }

  @Test
  fun acceptsLeapDaysOnlyInLeapYears() {
    assertDate("29/02/24", LocalDate.of(2024, 2, 29))
    assertDate("29 FEB 2000", LocalDate.of(2000, 2, 29))
    assertDate("2400-02-29", LocalDate.of(2400, 2, 29))
    listOf("29/02/25", "29 FEB 2100", "1900-02-29").forEach(::assertNoDates)
  }

  @Test
  fun rejectsImpossibleDaysAndMonthsWithoutRollingThemOver() {
    listOf(
            "00/10/26",
            "32/10/26",
            "31/04/26",
            "30/02/26",
            "12/00/26",
            "12/13/26",
            "2026-00-12",
            "2026-13-12",
            "2026-10-00",
            "2026-10-32",
            "31 APRIL 2026",
            "0 OCT 26")
        .forEach(::assertNoDates)
  }

  @Test
  fun numericDatesAreAlwaysDayFirstAndNeverRetriedAsMonthFirst() {
    assertDate("03/04/26", LocalDate.of(2026, 4, 3))
    assertNoDates("10/31/26")
  }

  @Test
  fun twoDigitYearsUseAFixedCenturyWithoutAClockBasedPivot() {
    listOf("00" to 2000, "01" to 2001, "49" to 2049, "50" to 2050, "99" to 2099).forEach {
        (shortYear, fullYear) ->
      listOf("01/01/$shortYear", "1 JAN $shortYear").forEach {
        assertDate(it, LocalDate.of(fullYear, 1, 1))
      }
    }
  }

  @Test
  fun fourDigitYearsArePreservedAndZeroIsRejected() {
    listOf("0001", "1999", "2000", "2099", "9999").forEach { year ->
      listOf("01/01/$year", "1 JAN $year", "$year-01-01").forEach {
        assertDate(it, LocalDate.of(year.toInt(), 1, 1))
      }
    }
    listOf("01/01/0000", "1 JAN 0000", "0000-01-01").forEach(::assertNoDates)
  }

  @Test
  fun keepsPastDatesForUserConfirmation() {
    assertDate("01/01/1990", LocalDate.of(1990, 1, 1))
  }

  @Test
  fun emptyOrUnrelatedTextHasNoCandidates() {
    listOf("", " \t\n", "Best before: see lid", "LOT 123456789", "Ingredients: milk, salt")
        .forEach(::assertNoDates)
  }

  @Test
  fun rejectsUnsupportedOrIncompleteFormats() {
    listOf(
            "10/2026",
            "12/10",
            "12 OCT",
            "OCT 2026",
            "OCT 12 2026",
            "2026/10/12",
            "2026.10.12",
            "2026-1-2",
            "12/10/6",
            "12/10/026",
            "12/10/20260",
            "12/10-26",
            "12.10/26",
            "12 FOO 2026",
            "12 OCTOBERLONG 2026",
            "12 OCTOBER 20260")
        .forEach(::assertNoDates)
  }

  @Test
  fun doesNotExtractPartialDatesFromLargerTokens() {
    listOf(
            "112/10/26",
            "12/110/26",
            "12/10/20260",
            "LOT12/10/26",
            "12/10/26ABC",
            "x2026-10-12",
            "2026-10-12T10:00",
            "1/12/10/26",
            "12/10/26/1",
            "12.10.26.123",
            "12-10-2026-01",
            "é12/10/26",
            "12/10/26é",
            "１２/10/26",
            "12/10/26３")
        .forEach(::assertNoDates)
  }

  @Test
  fun neverCombinesDatePartsAcrossLines() {
    listOf("12/\n10/26", "12\nOCT 2026", "12 OCT\r\n2026", "12\r/10/26").forEach(::assertNoDates)
  }

  @Test
  fun doesNotExtractDatesFromLongerNumericChainsWithSpacedSeparators() {
    listOf(
            "1 / 12 / 10 / 26",
            "12 / 10 / 26 / 1",
            "1 . 12 . 10 . 26",
            "12 - 10 - 2026 - 01",
            "1\t/\t12/10/26",
            "12/10/26\u00a0/\u00a01")
        .forEach(::assertNoDates)
  }

  @Test
  fun doesNotSilentlyRepairOcrLettersAsDigits() {
    listOf("I2/10/26", "12/1O/26", "12/10/2O26", "12 0CT 2026").forEach(::assertNoDates)
  }

  @Test
  fun parsingIsIndependentOfDefaultLocale() {
    val original = Locale.getDefault()
    try {
      listOf(Locale.US, Locale.FRANCE, Locale.forLanguageTag("tr-TR")).forEach { locale ->
        Locale.setDefault(locale)
        assertDate("12 OCT 2026", october12)
        assertDate("12 april 2026", LocalDate.of(2026, 4, 12))
        assertDate("03/04/26", LocalDate.of(2026, 4, 3))
      }
    } finally {
      Locale.setDefault(original)
    }
  }

  @Test
  fun candidatesRoundTripThroughExistingFormAndUtcStorageInEveryTimeZone() {
    val original = TimeZone.getDefault()
    try {
      listOf("UTC", "Pacific/Honolulu", "Pacific/Kiritimati").forEach { zone ->
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        val candidate = ExpiryDateParser.findDates("12 OCT 2026").single()
        val stored = ExpirationDates.toDate(candidate)
        assertEquals(october12, ExpirationDates.toLocalDate(stored))
        assertEquals("2026-10-12", ExpirationDates.formatInput(stored))
        assertEquals(ExpirationInput.Valid(stored), ExpirationDates.parse(candidate.toString()))
        assertEquals(0L, stored.time % 86_400_000L)
      }
    } finally {
      TimeZone.setDefault(original)
    }
  }

  private fun assertDate(text: String, date: LocalDate) {
    assertEquals(text, listOf(date), ExpiryDateParser.findDates(text))
  }

  private fun assertNoDates(text: String) {
    assertTrue(text, ExpiryDateParser.findDates(text).isEmpty())
  }
}
