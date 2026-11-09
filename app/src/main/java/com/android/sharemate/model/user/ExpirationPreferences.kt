// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.user

import kotlinx.coroutines.flow.Flow

/**
 * How one user wants to be reminded about expiring food. The values are always inside their allowed
 * ranges, because the fridge and the reminders both rely on those ranges: an instance outside them
 * cannot be built. Use [coerced] for values that were read from storage and cannot be trusted.
 *
 * @property soonDays An item expiring in this many days or fewer (and not yet expired) is "expiring
 *   soon"; the first reminder is sent on the day it enters that period. Within [SOON_DAYS_RANGE].
 * @property soonRepeatDays While expiring soon, remind again every this many days (in addition to
 *   the day before expiry). Null means no repeated reminder. Within [REPEAT_DAYS_RANGE].
 * @property expiredRepeatDays Once expired, remind again every this many days (in addition to the
 *   expiry day). Null means no repeated reminder. Within [REPEAT_DAYS_RANGE].
 * @throws IllegalArgumentException if a value is outside its allowed range.
 */
data class ExpirationPreferences(
    val soonDays: Int = DEFAULT_SOON_DAYS,
    val soonRepeatDays: Int? = DEFAULT_REPEAT_DAYS,
    val expiredRepeatDays: Int? = DEFAULT_REPEAT_DAYS,
) {
  init {
    require(soonDays in SOON_DAYS_RANGE) { "soonDays $soonDays is outside $SOON_DAYS_RANGE" }
    require(soonRepeatDays == null || soonRepeatDays in REPEAT_DAYS_RANGE) {
      "soonRepeatDays $soonRepeatDays is outside $REPEAT_DAYS_RANGE"
    }
    require(expiredRepeatDays == null || expiredRepeatDays in REPEAT_DAYS_RANGE) {
      "expiredRepeatDays $expiredRepeatDays is outside $REPEAT_DAYS_RANGE"
    }
  }

  companion object {
    /** Allowed number of days for the "expiring soon" window. */
    val SOON_DAYS_RANGE = 1..60

    /** Allowed number of days between two repeated reminders. */
    val REPEAT_DAYS_RANGE = 1..30

    /** Default "expiring soon" window. */
    const val DEFAULT_SOON_DAYS = 3

    /** Default number of days between two repeated reminders. */
    const val DEFAULT_REPEAT_DAYS = 1

    /** Builds preferences from untrusted values, bringing each one back into its allowed range. */
    fun coerced(soonDays: Int, soonRepeatDays: Int?, expiredRepeatDays: Int?) =
        ExpirationPreferences(
            soonDays = soonDays.coerceIn(SOON_DAYS_RANGE),
            soonRepeatDays = soonRepeatDays?.coerceIn(REPEAT_DAYS_RANGE),
            expiredRepeatDays = expiredRepeatDays?.coerceIn(REPEAT_DAYS_RANGE))
  }
}

/** Stores the [ExpirationPreferences] of each user, linked to their account. */
interface ExpirationPreferencesRepository {
  /** Emits the user's preferences, then every change. Defaults when none were saved. */
  fun observe(userId: String): Flow<ExpirationPreferences>

  /** Reads the user's current preferences. Defaults when none were saved. */
  suspend fun get(userId: String): ExpirationPreferences

  /** Saves all the user's preferences, replacing the stored ones. */
  suspend fun save(userId: String, preferences: ExpirationPreferences)
}
