// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.finance

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.finance.*
import com.android.sharemate.model.household.*
import com.android.sharemate.model.profile.UserProfileRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Presentation states. Amounts are cleared whenever observation fails or access changes. */
sealed interface BalancesUiState {
  /** Waiting for the first authoritative expense snapshot. */
  data object Loading : BalancesUiState
  /** The signed-in user has no access to shared-household balances. */
  data object Unavailable : BalancesUiState
  /** Observation failed; retry reloads membership and restarts observation. */
  data object Error : BalancesUiState
  /** The ledger cannot be calculated safely; a retry cannot repair its data. */
  data object InvalidData : BalancesUiState
  /** The server has not responded in time. Observation continues and may recover automatically. */
  data object Offline : BalancesUiState
  /** Committed expense balances and optional names; unknown names use a generic UI fallback. */
  data class Ready(
      val currentUserId: String,
      val summary: BalanceSummary,
      val memberNames: Map<String, String> = emptyMap()
  ) : BalancesUiState
}

/**
 * Shared access policy for the Receipts entry and the ViewModel; private history is out of scope.
 */
internal fun Household?.canViewBalances(userId: String?): Boolean =
    this != null && userId != null && type == FridgeType.SHARED && userId in memberIds

/** One household per instance; auth changes cancel all old observations and clear amounts. */
class BalancesViewModel(
    private val expenses: HouseholdExpenseRepository,
    private val households: HouseholdRepository,
    auth: AuthRepository,
    private val householdId: String,
    private val calculationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val profiles: UserProfileRepository? = null,
) : ViewModel() {
  private val mutableState = MutableStateFlow<BalancesUiState>(BalancesUiState.Loading)
  val state = mutableState.asStateFlow()
  private val retries = MutableStateFlow(0L)

  init {
    viewModelScope.launch {
      combine(auth.session, retries) { session, _ -> session }
          .collectLatest { session -> refresh(session) }
    }
  }

  private suspend fun refresh(session: AuthSession?) {
    mutableState.value = BalancesUiState.Loading
    coroutineScope {
      val waiting = launch {
        delay(INITIAL_LOAD_TIMEOUT_MS)
        if (mutableState.value == BalancesUiState.Loading) {
          mutableState.value = BalancesUiState.Offline
        }
      }
      try {
        val household = if (session == null) null else households.getHousehold(householdId)
        if (!household.canViewBalances(session?.uid)) {
          mutableState.value = BalancesUiState.Unavailable
        } else {
          observeBalances(checkNotNull(household), checkNotNull(session).uid)
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        // Log only the exception category, never financial records or user identifiers.
        Log.w("BalancesViewModel", "Observation failed: ${failure.javaClass.simpleName}")
        mutableState.value =
            if (failure is IllegalArgumentException) BalancesUiState.InvalidData
            else BalancesUiState.Error
      } finally {
        waiting.cancel()
      }
    }
  }

  private suspend fun observeBalances(household: Household, uid: String) {
    val names =
        profiles
            ?.observeMembers(householdId)
            ?.map { members ->
              members
                  .mapNotNull { member ->
                    member.displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { member.uid to it }
                  }
                  .toMap()
            }
            ?.onStart { emit(emptyMap()) }
            ?.catch { failure ->
              if (failure is CancellationException) throw failure
              Log.w("BalancesViewModel", "Names unavailable: ${failure.javaClass.simpleName}")
              emit(emptyMap())
            } ?: flowOf(emptyMap())
    combine(expenses.observeHouseholdExpenses(householdId), names) { records, memberNames ->
          withContext(calculationDispatcher) {
            BalancesUiState.Ready(
                uid,
                BalanceCalculator.calculate(
                    HouseholdFinanceSnapshot(householdId, records, emptyList()),
                    household.memberIds),
                memberNames)
          }
        }
        .collect { mutableState.value = it }
  }

  /** Restarts after observation failure or a server timeout; does nothing in other states. */
  fun retry() {
    if (state.value == BalancesUiState.Error || state.value == BalancesUiState.Offline) {
      mutableState.value = BalancesUiState.Loading
      retries.value += 1
    }
  }

  companion object {
    /** Time before the first server result is shown as unavailable rather than loading. */
    const val INITIAL_LOAD_TIMEOUT_MS = 10_000L
  }
}
