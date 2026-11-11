// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.user.ExpirationChange
import com.android.sharemate.model.user.ExpirationPreferences
import com.android.sharemate.model.user.ExpirationPreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the notification settings screen shows.
 *
 * @property preferences The user's current reminder preferences, as last reported by the store.
 * @property isLoaded True only while the preferences are being observed and have been reported at
 *   least once. It is false before the first report, and again as soon as the observation fails or
 *   is restarted by a retry, until the restarted observation reports. The screen never shows
 *   defaults that are about to be replaced, and no change is accepted when nothing would show it.
 * @property loadFailed Whether the preferences could not be read, at the start or later; the
 *   controls stay hidden until a retry succeeds, so nothing is saved on top of values the user
 *   never saw. It is never true together with [isLoaded].
 * @property saveFailed Whether the last change was refused and could not be saved.
 */
data class NotificationSettingsUiState(
    val preferences: ExpirationPreferences = ExpirationPreferences(),
    val isLoaded: Boolean = false,
    val loadFailed: Boolean = false,
    val saveFailed: Boolean = false,
)

/**
 * Reads and updates the expiration reminder preferences of one user.
 *
 * The screen only shows what the store reports. The store reports a change as soon as it is made
 * locally, before the server confirms it, even without a connection, and reports the previous value
 * again if the change is refused. This view model therefore never keeps edits of its own, so a late
 * report cannot undo a newer edit. Each change saves only its own value, so it cannot overwrite
 * another one either.
 *
 * @param repository where the preferences are read and saved.
 * @param userId the user whose preferences are edited.
 */
class NotificationSettingsViewModel(
    private val repository: ExpirationPreferencesRepository,
    private val userId: String,
) : ViewModel() {
  private val mutableState = MutableStateFlow(NotificationSettingsUiState())

  /** What the screen shows. */
  val state: StateFlow<NotificationSettingsUiState> = mutableState.asStateFlow()

  private var observing: Job? = null

  init {
    require(userId.isNotBlank()) { "A real user ID is required" }
    load()
  }

  /**
   * Tries again to read the preferences after a failure; does nothing otherwise. The controls come
   * back once the restarted observation reports.
   */
  fun retryLoad() {
    if (mutableState.value.loadFailed) load()
  }

  private fun load() {
    observing?.cancel()
    mutableState.update { it.copy(isLoaded = false, loadFailed = false) }
    observing =
        viewModelScope.launch {
          try {
            repository.observe(userId).collect { snapshot ->
              mutableState.update { it.copy(preferences = snapshot, isLoaded = true) }
            }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Exception) {
            Log.w(TAG, "Unable to read the expiry preferences", error)
            mutableState.update { it.copy(isLoaded = false, loadFailed = true) }
          }
        }
  }

  /** Saves the "expiring soon" window, brought into its allowed range. */
  fun setSoonDays(days: Int) =
      submit(ExpirationChange.SoonDays(days.coerceIn(ExpirationPreferences.SOON_DAYS_RANGE)))

  /** Saves the repeat interval while expiring soon; [days] null means no repeated reminder. */
  fun setSoonRepeatDays(days: Int?) =
      submit(
          ExpirationChange.SoonRepeatDays(days?.coerceIn(ExpirationPreferences.REPEAT_DAYS_RANGE)))

  /** Saves the repeat interval once expired; [days] null means no repeated reminder. */
  fun setExpiredRepeatDays(days: Int?) =
      submit(
          ExpirationChange.ExpiredRepeatDays(
              days?.coerceIn(ExpirationPreferences.REPEAT_DAYS_RANGE)))

  /** Hides the message about a change that could not be saved. */
  fun dismissSaveError() = mutableState.update { it.copy(saveFailed = false) }

  /**
   * Saves [change], unless the preferences are not being observed (not loaded yet, or the
   * observation failed) or it changes nothing.
   */
  private fun submit(change: ExpirationChange) {
    val current = mutableState.value
    if (!current.isLoaded || change.applyTo(current.preferences) == current.preferences) return
    mutableState.update { it.copy(saveFailed = false) }
    viewModelScope.launch {
      try {
        repository.update(userId, change)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        Log.w(TAG, "Unable to save the expiry preferences", error)
        mutableState.update { it.copy(saveFailed = true) }
      }
    }
  }

  private companion object {
    const val TAG = "NotificationSettings"
  }
}
