// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.finance.BalanceCalculator
import com.android.sharemate.model.finance.HouseholdFinanceRepository
import com.android.sharemate.model.receipt.RECEIPT_INITIAL_RETRY_MILLIS
import com.android.sharemate.model.receipt.RECEIPT_MAX_RETRY_MILLIS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
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
          .retryWhen { failure, attempt ->
            if (failure is CancellationException || failure !is Exception) throw failure
            emit(null)
            delay(
                (RECEIPT_INITIAL_RETRY_MILLIS * (1L shl attempt.coerceAtMost(5).toInt()))
                    .coerceAtMost(RECEIPT_MAX_RETRY_MILLIS))
            true
          }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}
