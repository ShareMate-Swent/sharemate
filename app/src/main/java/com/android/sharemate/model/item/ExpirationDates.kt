// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.item

import com.android.sharemate.model.user.ExpirationPreferences
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** How close an item is to its expiration date; see [ExpirationDates.status] for the exact rule. */
enum class ExpiryStatus {
  /** The expiry day itself or later. */
  EXPIRED,
  /** Not expired yet, and within the user's "expiring soon" window. */
  SOON,
  /** Beyond the window, or without an expiration date. */
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
  /** Default "soon" window, used until the user picks their own (see `ExpirationPreferences`). */
  const val SOON_THRESHOLD_DAYS = ExpirationPreferences.DEFAULT_SOON_DAYS.toLong()

  /** Days left on the expiry day itself: the item counts as expired from that day on. */
  const val EXPIRY_DAY = 0L

  /** Days left on the last day an item is "expiring soon", the day before it expires. */
  const val LAST_SOON_DAY = EXPIRY_DAY + 1

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

  /** Compact date for the inventory grid, formatted on the stored UTC day. */
  fun formatCompact(date: Date, locale: Locale = Locale.getDefault()): String =
      SimpleDateFormat("dd/MM/yyyy", locale)
          .apply { timeZone = TimeZone.getTimeZone("UTC") }
          .format(date)

  /** Days from [today] until [date]; negative once expired. Null without an expiration date. */
  fun daysUntil(date: Date?, today: LocalDate = LocalDate.now()): Long? =
      date?.let { ChronoUnit.DAYS.between(today, toLocalDate(it)) }

  /**
   * The single rule telling whether an item is expired, expiring soon or fine, shared by the fridge
   * and by the reminders so that they always agree: with [daysLeft] days left, the item is
   * [ExpiryStatus.EXPIRED] on the expiry day (0) and after, [ExpiryStatus.SOON] from 1 to
   * [soonThresholdDays] days, and [ExpiryStatus.NORMAL] beyond, or when it has no expiration date
   * ([daysLeft] null).
   */
  fun statusOfDaysLeft(daysLeft: Long?, soonThresholdDays: Long): ExpiryStatus =
      when {
        daysLeft == null -> ExpiryStatus.NORMAL
        daysLeft <= EXPIRY_DAY -> ExpiryStatus.EXPIRED
        daysLeft <= soonThresholdDays -> ExpiryStatus.SOON
        else -> ExpiryStatus.NORMAL
      }

  /** [status] of an item expiring on [date], seen from [today]. */
  fun status(
      date: Date?,
      today: LocalDate = LocalDate.now(),
      soonThresholdDays: Long = SOON_THRESHOLD_DAYS,
  ): ExpiryStatus = statusOfDaysLeft(daysUntil(date, today), soonThresholdDays)
}
