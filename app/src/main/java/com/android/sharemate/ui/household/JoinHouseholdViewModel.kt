// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.household

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.household.HouseholdRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Errors that can occur while joining a household. */
enum class JoinHouseholdError {
  NO_SESSION,
  INVALID_CODE,
  JOIN_FAILED
}

/** State shown by the join-household screen. */
data class JoinHouseholdUiState(
    val inviteCode: String = "",
    val isLoading: Boolean = false,
    val joined: Boolean = false,
    val error: JoinHouseholdError? = null,
)

/** Joins the currently authenticated user to a household. */
class JoinHouseholdViewModel(
    private val householdRepository: HouseholdRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
  private val mutableState = MutableStateFlow(JoinHouseholdUiState())
  val state = mutableState.asStateFlow()

  /** Updates the invite code using uppercase letters and digits, up to six characters. */
  fun updateInviteCode(value: String) {
    if (!state.value.isLoading) {
      val normalized =
          value.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(MAX_CODE_LENGTH)
      mutableState.update { it.copy(inviteCode = normalized, error = null) }
    }
  }

  /** Joins the household when six invite-code characters and a current session are available. */
  fun joinHousehold() {
    val current = state.value
    if (current.isLoading || current.inviteCode.length != MAX_CODE_LENGTH) return
    mutableState.update { it.copy(isLoading = true, error = null) }
    viewModelScope.launch {
      try {
        val session = authRepository.session.first()
        if (session == null) {
          mutableState.update { it.copy(error = JoinHouseholdError.NO_SESSION) }
        } else {
          householdRepository.joinHousehold(current.inviteCode, session.uid)
          mutableState.update { it.copy(joined = true) }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: IllegalArgumentException) {
        mutableState.update { it.copy(error = JoinHouseholdError.INVALID_CODE) }
      } catch (_: Exception) {
        mutableState.update { it.copy(error = JoinHouseholdError.JOIN_FAILED) }
      } finally {
        mutableState.update { it.copy(isLoading = false) }
      }
    }
  }

  companion object {
    const val MAX_CODE_LENGTH = 6
  }
}
