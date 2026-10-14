package com.android.sharemate.model.item

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Date

enum class ExpiryStatus {
  EXPIRED,
  SOON,
  NORMAL,
}

/** Un item est "SOON" s'il expire dans ce nombre de jours (inclus). Réutilisable pour les notifs. */
const val EXPIRY_SOON_THRESHOLD_DAYS = 3L

private val ISO_DATE_INPUT = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

/**
 * CONVENTION : une date d'expiration est un jour calendaire, stockée comme un [Date] à minuit UTC.
 * Toute conversion Date <-> LocalDate doit passer par les fonctions de ce fichier.
 */
fun LocalDate.toExpirationDate(): Date = Date.from(atStartOfDay(ZoneOffset.UTC).toInstant())

fun Date.toExpirationLocalDate(): LocalDate = toInstant().atZone(ZoneOffset.UTC).toLocalDate()

/** Format "yyyy-MM-dd", utilisé par le champ texte du formulaire. */
fun Date.toExpirationInput(): String = toExpirationLocalDate().toString()

/** Parse strictement "yyyy-MM-dd". Retourne null si le format ou la date est invalide. */
fun String.toExpirationDateOrNull(): Date? {
  val input = trim()
  if (!input.matches(ISO_DATE_INPUT)) return null
  return try {
    LocalDate.parse(input).toExpirationDate()
  } catch (_: DateTimeParseException) {
    null
  }
}

/** Positif = pas encore périmé, 0 = aujourd'hui, négatif = déjà périmé. */
fun Date.daysUntilExpiry(today: LocalDate = LocalDate.now()): Long =
  ChronoUnit.DAYS.between(today, toExpirationLocalDate())

fun Date.formatExpirationDate(): String =
  toExpirationLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

fun Date?.expiryStatus(today: LocalDate = LocalDate.now()): ExpiryStatus {
  if (this == null) return ExpiryStatus.NORMAL
  val days = daysUntilExpiry(today)
  return when {
    days < 0 -> ExpiryStatus.EXPIRED
    days <= EXPIRY_SOON_THRESHOLD_DAYS -> ExpiryStatus.SOON
    else -> ExpiryStatus.NORMAL
  }
}

/**
 * Items actifs qui expirent dans [days] jours ou moins (périmés inclus), triés du plus urgent au
 * moins urgent. Prévu pour ExpirationDatesNotification.
 */
fun List<Item>.expiringWithin(
  days: Long = EXPIRY_SOON_THRESHOLD_DAYS,
  today: LocalDate = LocalDate.now()
): List<Item> =
  filter { it.status == ItemStatus.ACTIVE }
    .mapNotNull { item -> item.expirationDate?.let { item to it.daysUntilExpiry(today) } }
    .filter { (_, daysLeft) -> daysLeft <= days }
    .sortedBy { (_, daysLeft) -> daysLeft }
    .map { (item, _) -> item }