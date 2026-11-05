// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.user.ExpirationPreferences
import com.android.sharemate.model.user.ExpirationPreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the notification settings screen shows.
 *
 * @property preferences The user's current reminder preferences.
 * @property isLoaded False until the saved preferences have been read, so the screen never shows
 *   defaults that are about to be replaced.
 * @property saveFailed Whether the last change could not be saved.
 */
data class NotificationSettingsUiState(
    val preferences: ExpirationPreferences = ExpirationPreferences(),
    val isLoaded: Boolean = false,
    val saveFailed: Boolean = false,
)

/** Reads and updates the expiration reminder preferences of one user. */
class NotificationSettingsViewModel(
    private val repository: ExpirationPreferencesRepository,
    private val userId: String,
) : ViewModel() {
  private val mutableState = MutableStateFlow(NotificationSettingsUiState())
  val state = mutableState.asStateFlow()

  init {
    viewModelScope.launch {
      try {
        repository.observe(userId).collect { saved ->
          mutableState.update { it.copy(preferences = saved, isLoaded = true) }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        // Keep showing what we have; the next change tries to save again.
        mutableState.update { it.copy(isLoaded = true) }
      }
    }
  }

  fun setSoonDays(days: Int) = update { it.copy(soonDays = days) }

  /** [days] null means no repeated reminder while expiring soon. */
  fun setSoonRepeatDays(days: Int?) = update { it.copy(soonRepeatDays = days) }

  /** [days] null means no repeated reminder once expired. */
  fun setExpiredRepeatDays(days: Int?) = update { it.copy(expiredRepeatDays = days) }

  fun dismissSaveError() = mutableState.update { it.copy(saveFailed = false) }

  private fun update(change: (ExpirationPreferences) -> ExpirationPreferences) {
    val updated = change(mutableState.value.preferences).sanitized()
    if (updated == mutableState.value.preferences) return
    mutableState.update { it.copy(preferences = updated, saveFailed = false) }
    viewModelScope.launch {
      try {
        repository.save(userId, updated)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableState.update { it.copy(saveFailed = true) }
      }
    }
  }
}
