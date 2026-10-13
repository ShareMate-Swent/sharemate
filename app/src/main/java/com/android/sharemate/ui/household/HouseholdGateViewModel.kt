package com.android.sharemate.ui.household

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.HouseholdRepository
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

/** Checks membership for one authenticated user; create a new instance when the user changes. */
class HouseholdGateViewModel(
    private val householdRepository: HouseholdRepository,
    private val userId: String
) : ViewModel() {
  private val mutableState = MutableStateFlow<HouseholdGateState>(HouseholdGateState.Loading)

  val state = mutableState.asStateFlow()

  private var refreshJob: Job? = null

  init {
    refresh()
  }

  /** Reloads membership after onboarding or an error, without starting duplicate requests. */
  fun refresh() {
    if (refreshJob?.isActive == true) return

    mutableState.value = HouseholdGateState.Loading
    refreshJob =
        viewModelScope.launch {
          try {
            val household = householdRepository.getHouseholdForUser(userId)

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
}
