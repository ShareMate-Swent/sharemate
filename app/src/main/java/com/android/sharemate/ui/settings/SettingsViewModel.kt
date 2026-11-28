// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
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
 * @property savedPseudo The user's public pseudo, if it could be loaded; the header shows it in
 *   place of the account name. Null while loading or when it could not be read.
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
    val savedPseudo: String? = null,
)

/**
 * Combines the current session, household and public pseudo into one [SettingsUiState].
 *
 * The [household] loaded by the household gate is shown at once, then fetched again so the member
 * count includes users who joined or left since. If that fetch fails, the loaded household stays.
 * The pseudo is only read, through [PseudoRepository], and read again by [refreshPseudo] when the
 * user comes back from the Edit profile page. A pseudo that cannot be read is not an error here:
 * the header then shows the account name. Editing the pseudo and the photo is done by
 * [EditProfileViewModel].
 *
 * @param authRepository Provides the current authenticated session.
 * @param householdRepository Reads the current household.
 * @param pseudoRepository Reads the signed-in user's public pseudo.
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
  private var pseudoJob: Job? = null
  private var currentUserId: String? = null

  init {
    // A signed-out session keeps the last profile until the auth gate leaves the screen.
    viewModelScope.launch {
      authRepository.session.filterNotNull().collect { session ->
        mutableState.update { it.withSession(session) }
      }
    }
    viewModelScope.launch {
      authRepository.session
          .filterNotNull()
          .map { it.uid }
          .distinctUntilChanged()
          .collectLatest { userId ->
            currentUserId = userId
            mutableState.update { it.copy(savedPseudo = null) }
            loadPseudo(userId)
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

  /**
   * Reads the pseudo again, without starting duplicate requests; a failure keeps the last value.
   */
  fun refreshPseudo() {
    val userId = currentUserId ?: return
    if (pseudoJob?.isActive == true) return
    pseudoJob = viewModelScope.launch { loadPseudo(userId) }
  }

  private suspend fun loadPseudo(userId: String) {
    try {
      val pseudo = pseudoRepository.getPseudo(userId)
      if (currentUserId == userId) mutableState.update { it.copy(savedPseudo = pseudo) }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      // The header falls back to the account name; the Edit profile page reports the failure.
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
