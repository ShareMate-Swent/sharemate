// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.item

import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class ExpiryStatus {
  EXPIRED,
  SOON,
  NORMAL,
}

/** Result of parsing the expiration date typed in the form. */
sealed interface ExpirationInput {
  /** The field is blank: no expiration date. */
  data object Empty : ExpirationInput

  data class Valid(val date: Date) : ExpirationInput

  data object Invalid : ExpirationInput
}

/**
 * An expiration date is a calendar day, not an instant. It is stored as midnight UTC and must
 * always be read back in UTC, so that every household member sees the same day whatever their time
 * zone. Only "today" depends on the device's time zone.
 */
object ExpirationDates {
  /** An item expiring in this many days or fewer (and not yet expired) is "soon". */
  const val SOON_THRESHOLD_DAYS = 3L

  private val INPUT_FORMAT = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

  /** Parses a strict `YYYY-MM-DD` input; surrounding spaces are ignored. */
  fun parse(input: String): ExpirationInput {
    val text = input.trim()
    if (text.isEmpty()) return ExpirationInput.Empty
    if (!INPUT_FORMAT.matches(text)) return ExpirationInput.Invalid
    return try {
      ExpirationInput.Valid(toDate(LocalDate.parse(text)))
    } catch (_: DateTimeParseException) {
      ExpirationInput.Invalid
    }
  }

  fun toDate(day: LocalDate): Date = Date.from(day.atStartOfDay(ZoneOffset.UTC).toInstant())

  fun toLocalDate(date: Date): LocalDate = date.toInstant().atZone(ZoneOffset.UTC).toLocalDate()

  /** Text shown in the form field; the inverse of [parse]. */
  fun formatInput(date: Date?): String = date?.let { toLocalDate(it).toString() }.orEmpty()

  /** Localized text for the item card, formatted on the stored UTC day. */
  fun formatDisplay(date: Date, locale: Locale = Locale.getDefault()): String =
      DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
          .apply { timeZone = TimeZone.getTimeZone("UTC") }
          .format(date)

  fun status(date: Date?, today: LocalDate = LocalDate.now()): ExpiryStatus {
    if (date == null) return ExpiryStatus.NORMAL
    val days = ChronoUnit.DAYS.between(today, toLocalDate(date))
    return when {
      days < 0 -> ExpiryStatus.EXPIRED
      days <= SOON_THRESHOLD_DAYS -> ExpiryStatus.SOON
      else -> ExpiryStatus.NORMAL
    }
  }
}
