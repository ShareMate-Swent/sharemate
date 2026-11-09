// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.fridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.item.ExpirationDates
import com.android.sharemate.model.user.ExpirationPreferencesProvider
import com.android.sharemate.ui.STATE_STOP_TIMEOUT_MILLIS
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Exposes the "expiring soon" window of one user, which the fridge cards use to flag their items.
 * It is kept apart from the fridge view model because it depends on the user's settings, not on the
 * inventory.
 *
 * @param preferences where the user's preferences are read; unreadable ones give the defaults.
 * @param userId the user whose window is exposed.
 */
class SoonThresholdViewModel(
    preferences: ExpirationPreferencesProvider,
    userId: String,
) : ViewModel() {
  init {
    require(userId.isNotBlank()) { "A real user ID is required" }
  }

  /** The user's "expiring soon" window in days; the default until their preferences load. */
  val soonThresholdDays: StateFlow<Long> =
      preferences
          .observe(userId)
          .map { it.soonDays.toLong() }
          .stateIn(
              viewModelScope,
              SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MILLIS),
              ExpirationDates.SOON_THRESHOLD_DAYS)
}
