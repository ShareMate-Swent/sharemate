// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: OpenAI Codex
package com.android.sharemate.ui.household

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.household.HouseholdRepository
import com.android.sharemate.model.household.PrivateHouseholdLeaveException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Errors that can occur while leaving a household. */
enum class LeaveHouseholdError {
  NO_SESSION,
  PRIVATE_HOUSEHOLD,
  LEAVE_FAILED
}

/**
 * State of the leave-household confirmation.
 *
 * @property isConfirming Whether the confirmation dialog is shown.
 * @property isLeaving Whether the request is in progress.
 * @property left Whether the user has left; the caller should then refresh membership.
 * @property error The last failure, shown in the dialog until the user retries or cancels.
 */
data class LeaveHouseholdUiState(
    val isConfirming: Boolean = false,
    val isLeaving: Boolean = false,
    val left: Boolean = false,
    val error: LeaveHouseholdError? = null,
)

/** Lets the currently authenticated user leave their household after confirming. */
class LeaveHouseholdViewModel(
    private val householdRepository: HouseholdRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
  private val mutableState = MutableStateFlow(LeaveHouseholdUiState())
  val state = mutableState.asStateFlow()

  /** Opens the confirmation dialog. */
  fun requestLeave() {
    val current = state.value
    if (current.isLeaving || current.left) return
    mutableState.update { it.copy(isConfirming = true, error = null) }
  }

  /** Closes the confirmation dialog, unless the request is already in progress. */
  fun cancel() {
    if (state.value.isLeaving) return
    mutableState.update { it.copy(isConfirming = false, error = null) }
  }

  /** Leaves the household; also used to retry after a failure. */
  fun confirmLeave() {
    val current = state.value
    if (!current.isConfirming || current.isLeaving || current.left) return
    if (current.error == LeaveHouseholdError.PRIVATE_HOUSEHOLD) return
    mutableState.update { it.copy(isLeaving = true, error = null) }
    viewModelScope.launch {
      try {
        val session = authRepository.session.first()
        if (session == null) {
          mutableState.update { it.copy(error = LeaveHouseholdError.NO_SESSION) }
        } else {
          householdRepository.leaveHousehold(session.uid)
          mutableState.update { it.copy(isConfirming = false, left = true) }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: PrivateHouseholdLeaveException) {
        mutableState.update { it.copy(error = LeaveHouseholdError.PRIVATE_HOUSEHOLD) }
      } catch (_: Exception) {
        mutableState.update { it.copy(error = LeaveHouseholdError.LEAVE_FAILED) }
      } finally {
        mutableState.update { it.copy(isLeaving = false) }
      }
    }
  }
}
