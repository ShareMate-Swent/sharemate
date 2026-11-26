// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.household

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.household.DeletedHouseholdStore
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.HouseholdRepository
import com.android.sharemate.model.profile.UserProfile
import com.android.sharemate.model.profile.UserProfileRepository
import com.android.sharemate.model.profile.toPublicProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Membership states used to choose between household onboarding and the app. */
sealed interface HouseholdGateState {
  data object Loading : HouseholdGateState

  data object NeedsHousehold : HouseholdGateState

  data class Ready(val household: Household) : HouseholdGateState

  data object Error : HouseholdGateState
}

/**
 * Checks membership for one authenticated user; create a new instance when the user changes.
 *
 * On start, and whenever [updateSession] reports a changed name or photo, it also saves the user's
 * public profile, so the other members of their household see their current name and photo.
 */
class HouseholdGateViewModel(
    private val householdRepository: HouseholdRepository,
    private val profileRepository: UserProfileRepository,
    private val deletedHouseholds: DeletedHouseholdStore,
    session: AuthSession,
) : ViewModel() {
  private val userId = session.uid
  private val mutableState = MutableStateFlow<HouseholdGateState>(HouseholdGateState.Loading)

  val state = mutableState.asStateFlow()

  private var refreshJob: Job? = null
  private var savedProfile: UserProfile? = null

  // Households the server confirmed deleted. A lookup that fails to reach the server falls back to
  // the local cache, which may still hold one of them, so they are never shown again. They are also
  // recorded in [deletedHouseholds], which survives a restart; this set covers the lookups in
  // flight and a record that cannot be saved or read.
  private val deletedHouseholdIds = mutableSetOf<String>()

  init {
    updateSession(session)
    refresh()
  }

  /**
   * Saves the public profile of [session] if its name or photo changed since the last save, e.g.
   * after a new profile photo. Sessions of another user are ignored.
   */
  fun updateSession(session: AuthSession) {
    val profile = session.toPublicProfile()
    if (profile.uid != userId || profile == savedProfile) return
    savedProfile = profile
    savePublicProfile(profile)
  }

  // Runs on its own: a failure must not block the app, and the next sign-in saves it again.
  private fun savePublicProfile(profile: UserProfile) {
    viewModelScope.launch {
      try {
        profileRepository.savePublicProfile(profile)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {}
    }
  }

  /**
   * Records that the server confirmed the deletion of [householdId], then reloads membership. That
   * household is ignored from now on, even if a lookup still returns it from the local cache, and
   * also after the app is restarted offline.
   */
  fun householdDeleted(householdId: String) {
    deletedHouseholdIds += householdId
    viewModelScope.launch {
      try {
        deletedHouseholds.markDeleted(userId, householdId)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        Log.w(
            TAG, "Could not record the deleted household; it is ignored for this session", failure)
      }
      refresh()
    }
  }

  /** Reloads membership after onboarding or an error, without starting duplicate requests. */
  fun refresh() {
    if (refreshJob?.isActive == true) return

    mutableState.value = HouseholdGateState.Loading
    refreshJob =
        viewModelScope.launch {
          try {
            val household =
                householdRepository.getHouseholdForUser(userId)?.takeUnless { isDeleted(it.id) }

            mutableState.value =
                if (household == null) {
                  HouseholdGateState.NeedsHousehold
                } else {
                  HouseholdGateState.Ready(household)
                }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            mutableState.value = HouseholdGateState.Error
          }
        }
  }

  private suspend fun isDeleted(householdId: String): Boolean {
    if (householdId in deletedHouseholdIds) return true
    return try {
      deletedHouseholds.isDeleted(userId, householdId)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      Log.w(TAG, "Could not read the deleted households; the lookup is trusted", failure)
      false
    }
  }

  private companion object {
    const val TAG = "HouseholdGate"
  }
}
