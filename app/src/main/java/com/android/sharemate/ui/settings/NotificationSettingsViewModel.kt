// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.user.ExpirationChange
import com.android.sharemate.model.user.ExpirationPreferences
import com.android.sharemate.model.user.ExpirationPreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
 * @property loadFailed Whether the saved preferences could not be read; the controls stay hidden
 *   until a retry succeeds, so nothing is saved on top of values the user never saw.
 * @property saveFailed Whether the last change could not be saved.
 */
data class NotificationSettingsUiState(
    val preferences: ExpirationPreferences = ExpirationPreferences(),
    val isLoaded: Boolean = false,
    val loadFailed: Boolean = false,
    val saveFailed: Boolean = false,
)

/** Reads and updates the expiration reminder preferences of one user. */
class NotificationSettingsViewModel(
    private val repository: ExpirationPreferencesRepository,
    private val userId: String,
) : ViewModel() {
  private val mutableState = MutableStateFlow(NotificationSettingsUiState())
  val state = mutableState.asStateFlow()

  private var saved = ExpirationPreferences()
  // Latest edit per setting, kept until its save is done and a snapshot confirms it.
  private val pending = mutableMapOf<Class<out ExpirationChange>, PendingEdit>()
  private var observing: Job? = null

  init {
    load()
  }

  /** Tries again to read the saved preferences after a failure. */
  fun retryLoad() {
    if (!mutableState.value.loadFailed) return
    load()
  }

  private fun load() {
    observing?.cancel()
    mutableState.update { it.copy(loadFailed = false) }
    observing =
        viewModelScope.launch {
          try {
            repository.observe(userId).collect { snapshot ->
              saved = snapshot
              clearConfirmed()
              publish(isLoaded = true)
            }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            mutableState.update { it.copy(loadFailed = true) }
          }
        }
  }

  fun setSoonDays(days: Int) =
      submit(ExpirationChange.SoonDays(days.coerceIn(ExpirationPreferences.SOON_DAYS_RANGE)))

  /** [days] null means no repeated reminder while expiring soon. */
  fun setSoonRepeatDays(days: Int?) =
      submit(
          ExpirationChange.SoonRepeatDays(days?.coerceIn(ExpirationPreferences.REPEAT_DAYS_RANGE)))

  /** [days] null means no repeated reminder once expired. */
  fun setExpiredRepeatDays(days: Int?) =
      submit(
          ExpirationChange.ExpiredRepeatDays(
              days?.coerceIn(ExpirationPreferences.REPEAT_DAYS_RANGE)))

  fun dismissSaveError() = mutableState.update { it.copy(saveFailed = false) }

  private fun clearConfirmed() =
      pending.values.removeAll { it.saveDone && it.change.applyTo(saved) == saved }

  private fun shown() =
      pending.values.fold(saved) { preferences, edit -> edit.change.applyTo(preferences) }

  private fun publish(
      isLoaded: Boolean = mutableState.value.isLoaded,
      saveFailed: Boolean? = null
  ) =
      mutableState.update {
        it.copy(
            preferences = shown(), isLoaded = isLoaded, saveFailed = saveFailed ?: it.saveFailed)
      }

  private fun submit(change: ExpirationChange) {
    if (!mutableState.value.isLoaded || change.applyTo(shown()) == shown()) return
    val edit = PendingEdit(change)
    pending[change.javaClass] = edit
    publish(saveFailed = false)
    viewModelScope.launch {
      try {
        repository.update(userId, change)
        edit.saveDone = true
        clearConfirmed()
        publish()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (pending[change.javaClass] == edit) pending.remove(change.javaClass)
        publish(saveFailed = true)
      }
    }
  }
}

private class PendingEdit(val change: ExpirationChange) {
  var saveDone = false
}
