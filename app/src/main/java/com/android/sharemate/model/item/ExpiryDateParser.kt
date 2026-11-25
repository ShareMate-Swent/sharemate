// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale

/**
 * Extracts candidate calendar dates from recognized packaging text, without Android or network
 * APIs.
 *
 * Numeric dates are day/month/year (never guessed from the device locale), or ISO `yyyy-MM-dd`.
 * Day-first dates accept `/`, `.` or `-` with the same separator on both sides of the month.
 * English month names and abbreviations are case-insensitive. Two-digit years always mean
 * 2000–2099; four-digit years must be in 1–9999. Day-first dates accept horizontal whitespace
 * around separators, but a date must fit on one line. Invalid calendar dates are ignored rather
 * than adjusted. Label punctuation may immediately precede a date. Numeric chains with extra
 * components are ignored, including numeric date ranges joined by a date separator; separate those
 * dates with a comma or newline instead.
 *
 * [findDates] returns distinct dates in text order, including past dates. It does not decide which
 * date is the expiry date: packaging may also show a production date, so the caller must ask the
 * user to confirm. Use [ExpirationDates.toDate] when saving a confirmed date to preserve midnight
 * UTC, or [LocalDate.toString] to fill the existing ISO form field.
 */
object ExpiryDateParser {
  private const val TWO_DIGIT_YEAR_BASE = 2000
  private const val SPACE = "[\\p{Zs}\\t]"
  private const val EDGE = "\\p{L}\\p{N}"
  private val datePattern =
      Regex(
          "(?<![$EDGE])(?:" +
              "(?<isoYear>[0-9]{4})-(?<isoMonth>[0-9]{2})-(?<isoDay>[0-9]{2})" +
              "|(?<day>[0-9]{1,2})$SPACE*(?<separator>[/.-])$SPACE*" +
              "(?<month>[0-9]{1,2})$SPACE*\\k<separator>$SPACE*(?<year>[0-9]{4}|[0-9]{2})" +
              "|(?<namedDay>[0-9]{1,2})$SPACE+(?<namedMonth>[a-zA-Z]{3,9})\\.?" +
              "$SPACE+(?<namedYear>[0-9]{4}|[0-9]{2})" +
              ")(?![\\p{L}\\p{N}/\\-]|\\.[0-9])")

  private val months =
      listOf(
              listOf("jan", "january"),
              listOf("feb", "february"),
              listOf("mar", "march"),
              listOf("apr", "april"),
              listOf("may"),
              listOf("jun", "june"),
              listOf("jul", "july"),
              listOf("aug", "august"),
              listOf("sep", "sept", "september"),
              listOf("oct", "october"),
              listOf("nov", "november"),
              listOf("dec", "december"))
          .flatMapIndexed { index, names -> names.map { it to index + 1 } }
          .toMap()

  /** Returns no candidates for empty text or text without a supported, complete, valid date. */
  fun findDates(text: String): List<LocalDate> =
      datePattern
          .findAll(text)
          .filterNot {
            it.groups["namedDay"] == null &&
                (continuesNumericChain(text, it.range.first - 1, -1) ||
                    continuesNumericChain(text, it.range.last + 1, 1))
          }
          .mapNotNull(::parseMatch)
          .distinct()
          .toList()

  // Do not extract a three-part date from a longer chain, even with spaces around its separators.
  private fun continuesNumericChain(text: String, start: Int, direction: Int): Boolean {
    var index = start
    fun skipSpaces() {
      while (index in text.indices &&
          (text[index] == '\t' ||
              Character.getType(text[index]) == Character.SPACE_SEPARATOR.toInt())) {
        index += direction
      }
    }
    skipSpaces()
    if (index !in text.indices || text[index] !in "/.-") return false
    index += direction
    skipSpaces()
    return index in text.indices && text[index] in '0'..'9'
  }

  private fun parseMatch(match: MatchResult): LocalDate? {
    val groups = match.groups
    // Each alternative in datePattern guarantees its corresponding fields are present.
    fun field(name: String): String = requireNotNull(groups[name]).value
    val day: Int
    val month: Int
    val yearText: String
    when {
      groups["isoYear"] != null -> {
        day = field("isoDay").toInt()
        month = field("isoMonth").toInt()
        yearText = field("isoYear")
      }
      groups["day"] != null -> {
        day = field("day").toInt()
        month = field("month").toInt()
        yearText = field("year")
      }
      else -> {
        day = field("namedDay").toInt()
        month = months[field("namedMonth").lowercase(Locale.ROOT)] ?: return null
        yearText = field("namedYear")
      }
    }
    val year = yearText.toInt() + if (yearText.length == 2) TWO_DIGIT_YEAR_BASE else 0
    if (year == 0) return null
    return try {
      LocalDate.of(year, month, day)
    } catch (_: DateTimeException) {
      null
    }
  }
}
