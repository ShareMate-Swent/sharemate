// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.auth.SignInProvider
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.HouseholdRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
 */
data class SettingsUiState(
    val email: String? = null,
    val displayName: String? = null,
    val photoUrl: String? = null,
    val provider: SignInProvider = SignInProvider.UNKNOWN,
    val householdName: String = "",
    val memberCount: Int = 0,
    val inviteCode: String = "",
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
    )
