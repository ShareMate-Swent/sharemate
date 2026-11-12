// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

/** Shared wire names for ledger documents and settlement callable payloads. */
internal object FinanceFields {
  const val ID = "id"
  const val HOUSEHOLD_ID = "householdId"
  const val PAID_BY = "paidBy"
  const val TOTAL_AMOUNT_CENTS = "totalAmountCents"
  const val SHARES = "shares"
  const val PAYER_ID = "payerId"
  const val RECIPIENT_ID = "recipientId"
  const val AMOUNT_CENTS = "amountCents"
}

internal object FinanceFunctions {
  const val RECORD_SETTLEMENT = "recordSettlement"
}
