// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.household

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.HouseholdRepository
import java.util.UUID
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
    val type: FridgeType = FridgeType.SHARED,
    val hasCreationOperation: Boolean = false,
)

/**
 * Creates a household for the current user. Android saved-state restoration of this navigation
 * entry reuses its operation ID; popping the entry starts a deliberate new operation. This is
 * saved-state recovery, not a durable journal surviving task dismissal.
 */
class CreateHouseholdViewModel(
    private val householdRepository: HouseholdRepository,
    private val authRepository: AuthRepository,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
  private val mutableState =
      MutableStateFlow(
          CreateHouseholdUiState(
              name = savedStateHandle[NAME] ?: "",
              type = FridgeType.valueOf(savedStateHandle[TYPE] ?: FridgeType.SHARED.name),
              inviteCode = savedStateHandle[INVITE],
              hasCreationOperation = savedStateHandle.contains(OPERATION)))
  val state = mutableState.asStateFlow()

  /** Updates the name before submission; retries stay bound to the original request. */
  fun updateName(name: String) {
    if (!state.value.isLoading && !state.value.hasCreationOperation) {
      savedStateHandle[NAME] = name
      mutableState.update { it.copy(name = name, error = null) }
    }
  }

  /** Selects the fridge type before creation starts. */
  fun updateType(type: FridgeType) {
    if (!state.value.isLoading && !state.value.hasCreationOperation) {
      savedStateHandle[TYPE] = type.name
      mutableState.update { it.copy(type = type, error = null) }
    }
  }

  /** Creates a household when the entered name and current session are valid. */
  fun createHousehold() {
    val current = state.value
    if (current.isLoading || current.name.isBlank() || current.inviteCode != null) return
    val name = current.name.trim()
    savedStateHandle[NAME] = name
    mutableState.update { it.copy(name = name, isLoading = true, error = null) }
    viewModelScope.launch {
      try {
        val session = authRepository.session.first()
        if (session == null) {
          mutableState.update { it.copy(error = CreateHouseholdError.NO_SESSION) }
        } else {
          // Keep the request and identity after any failure: cancellation may lose a successful
          // acknowledgement. SavedStateHandle restores them with this navigation entry.
          val operationId =
              savedStateHandle.get<String>(OPERATION)
                  ?: UUID.randomUUID().toString().also {
                    savedStateHandle[OWNER] = session.uid
                    savedStateHandle[OPERATION] = it
                  }
          check(savedStateHandle.get<String>(OWNER) == session.uid)
          mutableState.update { it.copy(hasCreationOperation = true) }
          val household =
              householdRepository.createHousehold(name, session.uid, current.type, operationId)
          savedStateHandle[INVITE] = household.inviteCode
          mutableState.update { it.copy(inviteCode = household.inviteCode, type = household.type) }
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

  private companion object {
    const val NAME = "creation_name"
    const val TYPE = "creation_type"
    const val INVITE = "creation_invite"
    const val OPERATION = "creation_operation"
    const val OWNER = "creation_owner"
  }
}
