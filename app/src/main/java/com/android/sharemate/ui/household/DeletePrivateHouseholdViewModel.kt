// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.household

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.household.PrivateHouseholdDeletionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Lets the signed-in user leave their private household by deleting it, after confirming.
 *
 * It reports its progress with [LeaveHouseholdUiState], like [LeaveHouseholdViewModel], so the same
 * dialog shows both. A failure keeps the dialog open; confirming again retries, and the server only
 * redoes what is left. [LeaveHouseholdUiState.left] becomes true once the server confirmed the
 * deletion; nothing is deleted locally before that.
 *
 * @param householdId The private household to delete.
 */
class DeletePrivateHouseholdViewModel(
    private val householdId: String,
    private val deletion: PrivateHouseholdDeletionRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
  private val mutableState = MutableStateFlow(LeaveHouseholdUiState())
  val state = mutableState.asStateFlow()

  /** Opens the confirmation dialog. */
  fun requestDelete() {
    val current = state.value
    if (current.isLeaving || current.left) return
    mutableState.update { it.copy(isConfirming = true, error = null) }
  }

  /** Closes the confirmation dialog, unless the deletion is already in progress. */
  fun cancel() {
    if (state.value.isLeaving) return
    mutableState.update { it.copy(isConfirming = false, error = null) }
  }

  /** Deletes the household; also used to retry after a failure. */
  fun confirmDelete() {
    val current = state.value
    if (!current.isConfirming || current.isLeaving || current.left) return
    mutableState.update { it.copy(isLeaving = true, error = null) }
    viewModelScope.launch {
      try {
        deleteForCurrentSession()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableState.update { it.copy(error = LeaveHouseholdError.DELETE_FAILED) }
      } finally {
        mutableState.update { it.copy(isLeaving = false) }
      }
    }
  }

  private suspend fun deleteForCurrentSession() {
    if (authRepository.session.first() == null) {
      mutableState.update { it.copy(error = LeaveHouseholdError.NO_SESSION) }
      return
    }
    deletion.deletePrivateHousehold(householdId)
    mutableState.update { it.copy(isConfirming = false, left = true) }
  }
}
