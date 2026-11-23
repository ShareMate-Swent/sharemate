// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.finance.BalanceCalculator
import com.android.sharemate.model.finance.HouseholdFinanceRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Only server-confirmed ledger revisions are presented as a balance. */
class ReceiptBalanceViewModel(
    repository: HouseholdFinanceRepository,
    householdId: String,
    userId: String
) : ViewModel() {
  /** Net amount in cents: positive means owed to this user; null means not yet confirmed. */
  val balance =
      repository
          .observeHouseholdFinances(householdId)
          .map { snapshot ->
            if (snapshot.isSynced)
                BalanceCalculator.calculate(snapshot, listOf(userId)).netBalancesCents[userId]
            else null
          }
          .catch { emit(null) }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}
