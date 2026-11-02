// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import java.util.Collections

/**
 * A shared expense with explicit per-user shares in [FinanceAmounts.CURRENCY_CODE] cents.
 *
 * Shares include every participant's agreed amount; zero shares are allowed. The payer need not
 * consume a share. This model neither chooses participants nor performs equal splitting/rounding.
 * IDs are opaque and must not be blank. Validation does not establish household membership or
 * authorization; a trusted backend must enforce those independently.
 */
class SharedExpense(
    val id: String,
    val householdId: String,
    val paidBy: String,
    val totalAmountCents: Long,
    shares: Map<String, Long>,
) {
  val shares: Map<String, Long> = Collections.unmodifiableMap(LinkedHashMap(shares))

  init {
    require(id.isNotBlank()) { "Expense ID must not be blank" }
    require(householdId.isNotBlank()) { "Household ID must not be blank" }
    require(paidBy.isNotBlank()) { "Payer ID must not be blank" }
    require(totalAmountCents > 0) { "Expense amount must be positive" }
    require(this.shares.isNotEmpty()) { "Expense shares must not be empty" }
    var remaining = totalAmountCents
    this.shares.forEach { (userId, amount) ->
      require(userId.isNotBlank()) { "Participant ID must not be blank" }
      require(amount >= 0 && amount <= remaining) { "Invalid expense share" }
      remaining -= amount
    }
    require(remaining == 0L) { "Expense shares must sum to the total" }
  }

  override fun equals(other: Any?): Boolean =
      other is SharedExpense &&
          id == other.id &&
          householdId == other.householdId &&
          paidBy == other.paidBy &&
          totalAmountCents == other.totalAmountCents &&
          shares == other.shares

  override fun hashCode(): Int {
    var result = id.hashCode()
    result = 31 * result + householdId.hashCode()
    result = 31 * result + paidBy.hashCode()
    result = 31 * result + totalAmountCents.hashCode()
    return 31 * result + shares.hashCode()
  }

  override fun toString(): String =
      "SharedExpense(id=$id, householdId=$householdId, paidBy=$paidBy, " +
          "totalAmountCents=$totalAmountCents, shares=$shares)"
}
