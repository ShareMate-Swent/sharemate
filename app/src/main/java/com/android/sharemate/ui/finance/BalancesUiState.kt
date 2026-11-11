// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.finance

import com.android.sharemate.model.finance.BalanceSummary
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.Household

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

/** Access policy for the Receipts entry; private history is out of scope. */
internal fun Household?.canViewBalances(userId: String?): Boolean =
    this != null && userId != null && type == FridgeType.SHARED && userId in memberIds
