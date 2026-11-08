// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.household

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.profile.UserProfile
import com.android.sharemate.model.profile.UserProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A household member as the members list shows them.
 *
 * @property uid User identifier.
 * @property displayName Account name; null when the UI should fall back to [email].
 * @property email Account email; null when the provider does not share one.
 * @property photoUrl Account photo URL; null when the UI should show a placeholder avatar.
 * @property isCurrentUser Whether this member is the signed-in user, shown as "You".
 */
data class HouseholdMember(
    val uid: String,
    val displayName: String? = null,
    val email: String? = null,
    val photoUrl: String? = null,
    val isCurrentUser: Boolean = false,
)

/**
 * Everything the members list shows.
 *
 * @property members The signed-in user first, then the others in the household's member order.
 * @property isLoading Whether the first list is still loading.
 * @property hasError Whether the list could not be loaded; [HouseholdMembersViewModel.retry] clears
 *   it.
 */
data class HouseholdMembersUiState(
    val members: List<HouseholdMember> = emptyList(),
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
)

/**
 * Keeps the members of [householdId] up to date while the list is open, so joins and leaves show
 * without restarting the app. It depends only on [UserProfileRepository], not on Firebase.
 *
 * @param currentUserId The signed-in user, marked as "You" and listed first.
 */
class HouseholdMembersViewModel(
    private val profileRepository: UserProfileRepository,
    private val householdId: String,
    private val currentUserId: String,
) : ViewModel() {
  private val mutableState = MutableStateFlow(HouseholdMembersUiState())
  val state = mutableState.asStateFlow()

  private var observeJob: Job? = null

  init {
    observe()
  }

  /** Loads the list again after an error. Does nothing while the list loads or is shown. */
  fun retry() {
    if (state.value.hasError) observe()
  }

  private fun observe() {
    observeJob?.cancel()
    mutableState.update { it.copy(isLoading = true, hasError = false) }
    observeJob =
        viewModelScope.launch {
          try {
            profileRepository.observeMembers(householdId).collect { profiles ->
              mutableState.value =
                  HouseholdMembersUiState(members = profiles.toMembers(), isLoading = false)
            }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            // The repository's flow ends on its first failure, so only retry() starts it again.
            mutableState.update { it.copy(isLoading = false, hasError = true) }
          }
        }
  }

  private fun List<UserProfile>.toMembers(): List<HouseholdMember> =
      map { profile ->
            HouseholdMember(
                uid = profile.uid,
                displayName = profile.displayName,
                email = profile.email,
                photoUrl = profile.photoUrl,
                isCurrentUser = profile.uid == currentUserId,
            )
          }
          // sortedBy is stable, so the others keep the household's member order.
          .sortedBy { !it.isCurrentUser }
}
