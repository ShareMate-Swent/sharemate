// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.auth.AuthError
import com.android.sharemate.model.auth.AuthException
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.auth.SignInProvider
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.HouseholdRepository
import com.android.sharemate.model.user.PseudoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why switching to another Google account failed. */
enum class SettingsAccountError {
  GOOGLE_UNAVAILABLE,
  NETWORK,
  SWITCH_FAILED
}

/** Why the pseudo could not be loaded or saved. */
enum class SettingsPseudoError {
  LOAD_FAILED,
  SAVE_FAILED
}

/**
 * Everything the Settings screen shows: the signed-in profile, its account and its household.
 *
 * @property email Account email, if the provider shares one.
 * @property displayName Account name; null when the UI should fall back to [email].
 * @property photoUrl Account photo URL; null when the UI should show a placeholder avatar.
 * @property provider How the user signed in.
 * @property householdName Name of the user's household.
 * @property memberCount Number of members of the household, the user included.
 * @property inviteCode Code other users enter to join the household.
 * @property fridgeType Whether the household is private (no one else can join) or shared.
 * @property isSwitchingAccount Whether a Google account switch is in progress.
 * @property accountError The last account switch failure, cleared by the next attempt.
 * @property pseudo The editable public pseudo.
 * @property savedPseudo The last pseudo successfully loaded or saved.
 * @property isLoadingPseudo Whether the public pseudo is being loaded.
 * @property isSavingPseudo Whether a pseudo update is in progress.
 * @property pseudoError The last pseudo load or save failure.
 */
data class SettingsUiState(
    val email: String? = null,
    val displayName: String? = null,
    val photoUrl: String? = null,
    val provider: SignInProvider = SignInProvider.UNKNOWN,
    val householdName: String = "",
    val memberCount: Int = 0,
    val inviteCode: String = "",
    val fridgeType: FridgeType = FridgeType.SHARED,
    val isSwitchingAccount: Boolean = false,
    val accountError: SettingsAccountError? = null,
    val pseudo: String = "",
    val savedPseudo: String? = null,
    val isLoadingPseudo: Boolean = false,
    val isSavingPseudo: Boolean = false,
    val pseudoError: SettingsPseudoError? = null,
)

/**
 * Combines the current session, household and public pseudo into one [SettingsUiState].
 *
 * The [household] loaded by the household gate is shown at once, then fetched again so the member
 * count includes users who joined or left since. If that fetch fails, the loaded household stays.
 * The pseudo is loaded and saved through [PseudoRepository].
 *
 * @param authRepository Provides the current authenticated session.
 * @param householdRepository Reads the current household.
 * @param pseudoRepository Reads and updates the signed-in user's public pseudo.
 * @param household The household already loaded by the household gate.
 */
class SettingsViewModel(
    private val authRepository: AuthRepository,
    private val householdRepository: HouseholdRepository,
    private val pseudoRepository: PseudoRepository,
    household: Household,
) : ViewModel() {
  private val householdId = household.id
  private val mutableState = MutableStateFlow(SettingsUiState().withHousehold(household))
  /** Current account and household data rendered by Settings. */
  val state = mutableState.asStateFlow()

  private var refreshJob: Job? = null
  private var currentUserId: String? = null

  init {
    // A signed-out session keeps the last profile until the auth gate leaves the screen.
    viewModelScope.launch {
      authRepository.session.filterNotNull().collectLatest { session ->
        val isNewUser = currentUserId != session.uid
        currentUserId = session.uid
        mutableState.update {
          val updated = it.withSession(session)
          if (isNewUser) {
            updated.copy(
                pseudo = "",
                savedPseudo = null,
                isLoadingPseudo = true,
                isSavingPseudo = false,
                pseudoError = null)
          } else {
            updated
          }
        }
        if (isNewUser) viewModelScope.launch { loadPseudo(session.uid) }
      }
    }
    refreshHousehold()
  }

  /**
   * Signs in with another Google account without signing out first, so cancelling the account
   * picker keeps the current session. Firebase then replaces the user, and the household gate loads
   * the new user's household.
   *
   * The picker runs in the caller's UI scope; only the sign-in uses the retained ViewModel scope.
   */
  suspend fun switchGoogleAccount(tokenProvider: suspend () -> String?) {
    if (state.value.isSwitchingAccount) return
    mutableState.update { it.copy(isSwitchingAccount = true, accountError = null) }
    var handedOff = false
    try {
      val token = tokenProvider() ?: return
      if (token.isBlank()) throw AuthException(AuthError.INVALID_CREDENTIALS)
      viewModelScope.launch { signInWithGoogle(token) }
      handedOff = true
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: AuthException) {
      mutableState.update { it.copy(accountError = failure.error.toAccountError()) }
    } catch (_: Exception) {
      mutableState.update { it.copy(accountError = SettingsAccountError.GOOGLE_UNAVAILABLE) }
    } finally {
      if (!handedOff) mutableState.update { it.copy(isSwitchingAccount = false) }
    }
  }

  private suspend fun signInWithGoogle(token: String) {
    try {
      authRepository.signInWithGoogle(token)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: AuthException) {
      mutableState.update { it.copy(accountError = failure.error.toAccountError()) }
    } catch (_: Exception) {
      mutableState.update { it.copy(accountError = SettingsAccountError.SWITCH_FAILED) }
    } finally {
      mutableState.update { it.copy(isSwitchingAccount = false) }
    }
  }

  /** Fetches the household again, without starting duplicate requests. */
  fun refreshHousehold() {
    if (refreshJob?.isActive == true) return
    refreshJob =
        viewModelScope.launch {
          try {
            householdRepository.getHousehold(householdId)?.let { latest ->
              mutableState.update { it.withHousehold(latest) }
            }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            // The household loaded by the gate is still a valid, if possibly outdated, view.
          }
        }
  }

  /** Updates the pseudo being edited without saving it yet. */
  fun updatePseudo(pseudo: String) {
    mutableState.update {
      it.copy(
          pseudo = pseudo,
          pseudoError =
              it.pseudoError.takeUnless { error -> error == SettingsPseudoError.SAVE_FAILED })
    }
  }

  /** Saves the edited pseudo as the public profile name for the current signed-in user. */
  fun savePseudo() {
    val userId = currentUserId ?: return
    val pseudo = state.value.pseudo.trim()
    if (state.value.isLoadingPseudo ||
        state.value.isSavingPseudo ||
        pseudo.isEmpty() ||
        pseudo == state.value.savedPseudo) {
      return
    }
    mutableState.update { it.copy(isSavingPseudo = true, pseudoError = null) }
    viewModelScope.launch {
      try {
        pseudoRepository.setPseudo(userId, pseudo)
        if (currentUserId == userId) {
          mutableState.update {
            it.copy(
                pseudo = pseudo,
                savedPseudo = pseudo,
                isSavingPseudo = false,
                pseudoError = null)
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (currentUserId == userId) {
          mutableState.update {
            it.copy(isSavingPseudo = false, pseudoError = SettingsPseudoError.SAVE_FAILED)
          }
        }
      }
    }
  }

  private suspend fun loadPseudo(userId: String) {
    try {
      val pseudo = pseudoRepository.getPseudo(userId)
      if (currentUserId == userId) {
        mutableState.update {
          it.copy(
              pseudo = pseudo.orEmpty(),
              savedPseudo = pseudo,
              isLoadingPseudo = false,
              pseudoError = null)
        }
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      if (currentUserId == userId) {
        mutableState.update {
          it.copy(isLoadingPseudo = false, pseudoError = SettingsPseudoError.LOAD_FAILED)
        }
      }
    }
  }
}

private fun SettingsUiState.withSession(session: AuthSession) =
    copy(
        email = session.email,
        displayName = session.displayName,
        photoUrl = session.photoUrl,
        provider = session.provider,
    )

private fun SettingsUiState.withHousehold(household: Household) =
    copy(
        householdName = household.name,
        memberCount = household.memberIds.size,
        inviteCode = household.inviteCode,
        fridgeType = household.type,
    )

private fun AuthError.toAccountError() =
    when (this) {
      AuthError.GOOGLE_UNAVAILABLE -> SettingsAccountError.GOOGLE_UNAVAILABLE
      AuthError.NETWORK -> SettingsAccountError.NETWORK
      else -> SettingsAccountError.SWITCH_FAILED
    }
