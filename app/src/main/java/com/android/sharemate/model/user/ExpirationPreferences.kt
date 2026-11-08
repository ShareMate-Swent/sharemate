// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.user

import kotlinx.coroutines.flow.Flow

/**
 * How one user wants to be reminded about expiring food.
 *
 * @property soonDays An item expiring in this many days or fewer is "expiring soon"; the first
 *   reminder is sent on the day it enters that period.
 * @property soonRepeatDays While expiring soon, remind again every this many days (in addition to
 *   the day before expiry). Null means no repeated reminder.
 * @property expiredRepeatDays Once expired, remind again every this many days (in addition to the
 *   expiry day). Null means no repeated reminder.
 */
data class ExpirationPreferences(
    val soonDays: Int = DEFAULT_SOON_DAYS,
    val soonRepeatDays: Int? = DEFAULT_REPEAT_DAYS,
    val expiredRepeatDays: Int? = DEFAULT_REPEAT_DAYS,
) {
  /** Same preferences with every value brought back into its allowed range. */
  fun sanitized() =
      ExpirationPreferences(
          soonDays = soonDays.coerceIn(SOON_DAYS_RANGE),
          soonRepeatDays = soonRepeatDays?.coerceIn(REPEAT_DAYS_RANGE),
          expiredRepeatDays = expiredRepeatDays?.coerceIn(REPEAT_DAYS_RANGE))

  companion object {
    val SOON_DAYS_RANGE = 1..60
    val REPEAT_DAYS_RANGE = 1..30
    const val DEFAULT_SOON_DAYS = 3
    const val DEFAULT_REPEAT_DAYS = 1
  }
}

/** Stores the [ExpirationPreferences] of each user, linked to their account. */
interface ExpirationPreferencesRepository {
  /** Emits the user's preferences, then every change. Defaults when none were saved. */
  fun observe(userId: String): Flow<ExpirationPreferences>

  /** Reads the user's current preferences. Defaults when none were saved. */
  suspend fun get(userId: String): ExpirationPreferences

  suspend fun save(userId: String, preferences: ExpirationPreferences)
}
