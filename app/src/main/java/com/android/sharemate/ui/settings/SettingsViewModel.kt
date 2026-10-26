// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why switching to another Google account failed. */
enum class SettingsAccountError {
  GOOGLE_UNAVAILABLE,
  NETWORK,
  SWITCH_FAILED
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
)

/**
 * Combines the current session and household into one [SettingsUiState].
 *
 * The [household] loaded by the household gate is shown at once, then fetched again so the member
 * count includes users who joined or left since. If that fetch fails, the loaded household stays.
 */
class SettingsViewModel(
    private val authRepository: AuthRepository,
    private val householdRepository: HouseholdRepository,
    household: Household,
) : ViewModel() {
  private val householdId = household.id
  private val mutableState = MutableStateFlow(SettingsUiState().withHousehold(household))
  val state = mutableState.asStateFlow()

  private var refreshJob: Job? = null

  init {
    // A signed-out session keeps the last profile until the auth gate leaves the screen.
    viewModelScope.launch {
      authRepository.session.filterNotNull().collect { session ->
        mutableState.update { it.withSession(session) }
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
