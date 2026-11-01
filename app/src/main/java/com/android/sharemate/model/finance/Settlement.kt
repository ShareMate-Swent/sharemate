// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

/**
 * A recorded reimbursement from [payerId] to [recipientId], in [FinanceAmounts.CURRENCY_CODE]
 * cents.
 *
 * [id] is the stable operation identity. Retain the entire record across retries and process
 * recreation; a new ID represents a deliberate new payment. This records a reported reimbursement,
 * not a bank transfer. Client-side validation provides no authorization guarantee.
 */
data class Settlement(
    val id: String,
    val householdId: String,
    val payerId: String,
    val recipientId: String,
    val amountCents: Long,
) {
  init {
    require(id.isNotBlank()) { "Settlement ID must not be blank" }
    require(householdId.isNotBlank()) { "Household ID must not be blank" }
    require(payerId.isNotBlank()) { "Payer ID must not be blank" }
    require(recipientId.isNotBlank()) { "Recipient ID must not be blank" }
    require(payerId != recipientId) { "A settlement must involve different users" }
    require(amountCents > 0) { "Settlement amount must be positive" }
  }
}
