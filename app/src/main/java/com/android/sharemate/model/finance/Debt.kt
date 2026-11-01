// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

/** A positive amount [debtorId] owes [creditorId], in [FinanceAmounts.CURRENCY_CODE] cents. */
data class Debt(val debtorId: String, val creditorId: String, val amountCents: Long) {
  init {
    require(debtorId.isNotBlank()) { "Debtor ID must not be blank" }
    require(creditorId.isNotBlank()) { "Creditor ID must not be blank" }
    require(debtorId != creditorId) { "A debt must involve different users" }
    require(amountCents > 0) { "Debt amount must be positive" }
  }
}
