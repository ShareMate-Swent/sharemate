package com.android.sharemate.model.fridgeManager

import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.toLocalDateUtc
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object ExpirationReminderPolicy {
    val DEFAULT_OFFSETS_DAYS = listOf(-2, 0, 2) // J-2, J, J+2

    /**
     * For recorded dates (Expiration): Always write and read in UTC to prevent time differences from shifting the calendar day.
     * For the current date (Today): Always use the user device's local time zone (LocalDate.now()).
     *
     * @return le décalage (en jours) pour lequel une notification est due aujourd'hui, ou null.
     */
    fun dueOffset(
        item: Item,
        today: LocalDate,
        offsets: List<Int> = DEFAULT_OFFSETS_DAYS,
    ): Int? {
        val expiry = item.expirationDate?.toLocalDateUtc() ?: return null
        val daysFromExpiry = ChronoUnit.DAYS.between(expiry, today).toInt() // négatif = avant
        return offsets.firstOrNull { it == daysFromExpiry }
    }
}

