// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.finance.BalanceCalculator
import com.android.sharemate.model.finance.BalanceSummary
import com.android.sharemate.model.finance.HouseholdExpenseRepository
import com.android.sharemate.model.finance.HouseholdFinanceSnapshot
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.HouseholdRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface BalancesUiState {
  data object Loading : BalancesUiState

  data object Unavailable : BalancesUiState

  data object Error : BalancesUiState

  data class Ready(val currentUserId: String, val summary: BalanceSummary) : BalancesUiState
}

/** One household per instance; the navigation gate replaces it when the household changes. */
class BalancesViewModel(
    private val expenses: HouseholdExpenseRepository,
    private val households: HouseholdRepository,
    auth: AuthRepository,
    private val householdId: String,
    private val calculationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
  private val mutableState = MutableStateFlow<BalancesUiState>(BalancesUiState.Loading)
  val state = mutableState.asStateFlow()
  private val retries = MutableStateFlow(0L)

  init {
    viewModelScope.launch {
      combine(auth.session, retries) { session, _ -> session }
          .collectLatest { session ->
            mutableState.value = BalancesUiState.Loading
            try {
              val household = if (session == null) null else households.getHousehold(householdId)
              if (session == null ||
                  household == null ||
                  household.type != FridgeType.SHARED ||
                  session.uid !in household.memberIds) {
                mutableState.value = BalancesUiState.Unavailable
              } else {
                expenses.observeHouseholdExpenses(householdId).collect { records ->
                  val summary =
                      withContext(calculationDispatcher) {
                        BalanceCalculator.calculate(
                            HouseholdFinanceSnapshot(householdId, records, emptyList()),
                            household.memberIds)
                      }
                  mutableState.value = BalancesUiState.Ready(session.uid, summary)
                }
              }
            } catch (cancelled: CancellationException) {
              throw cancelled
            } catch (_: Exception) {
              // Never retain financial amounts after an access, parsing, or listener failure.
              mutableState.value = BalancesUiState.Error
            }
          }
    }
  }

  fun retry() {
    if (state.value == BalancesUiState.Error) {
      mutableState.value = BalancesUiState.Loading
      retries.value += 1
    }
  }
}
