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

/** Errors that can occur while creating a household. */
enum class CreateHouseholdError {
  NO_SESSION,
  CREATION_FAILED
}

/** State shown by the create-household screen. */
data class CreateHouseholdUiState(
    val name: String = "",
    val isLoading: Boolean = false,
    val inviteCode: String? = null,
    val error: CreateHouseholdError? = null,
)

/** Creates a household for the currently authenticated user. */
class CreateHouseholdViewModel(
    private val householdRepository: HouseholdRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
  private val mutableState = MutableStateFlow(CreateHouseholdUiState())
  val state = mutableState.asStateFlow()

  /** Updates the household name unless a request is in progress. */
  fun updateName(name: String) {
    if (!state.value.isLoading) mutableState.update { it.copy(name = name, error = null) }
  }

  /** Creates a household when the entered name and current session are valid. */
  fun createHousehold() {
    val current = state.value
    if (current.isLoading || current.name.isBlank() || current.inviteCode != null) return
    val name = current.name.trim()
    mutableState.update { it.copy(name = name, isLoading = true, error = null) }
    viewModelScope.launch {
      try {
        val session = authRepository.session.first()
        if (session == null) {
          mutableState.update { it.copy(error = CreateHouseholdError.NO_SESSION) }
        } else {
          val household = householdRepository.createHousehold(name, session.uid)
          mutableState.update { it.copy(inviteCode = household.inviteCode) }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableState.update { it.copy(error = CreateHouseholdError.CREATION_FAILED) }
      } finally {
        mutableState.update { it.copy(isLoading = false) }
      }
    }
  }
}
