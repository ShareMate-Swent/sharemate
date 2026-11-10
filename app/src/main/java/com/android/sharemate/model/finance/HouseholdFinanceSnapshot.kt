// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import java.util.Collections

/**
 * A coherent household ledger; records belong to this household and IDs are unique per kind.
 * Current member identities come separately from the household repository; historical participants
 * remain in the ledger.
 */
class HouseholdFinanceSnapshot(
    val householdId: String,
    expenses: List<SharedExpense>,
    settlements: List<Settlement>,
    /** False for cached/pending data, including an empty cache whose server ledger is unknown. */
    val isSynced: Boolean = true,
) {
  val expenses: List<SharedExpense> = Collections.unmodifiableList(ArrayList(expenses))
  val settlements: List<Settlement> = Collections.unmodifiableList(ArrayList(settlements))

  init {
    require(householdId.isNotBlank()) { "Household ID must not be blank" }
    require(this.expenses.all { it.householdId == householdId }) { "Expense household mismatch" }
    require(this.settlements.all { it.householdId == householdId }) {
      "Settlement household mismatch"
    }
    require(this.expenses.map { it.id }.distinct().size == this.expenses.size) {
      "Duplicate expense ID"
    }
    require(this.settlements.map { it.id }.distinct().size == this.settlements.size) {
      "Duplicate settlement ID"
    }
  }

  override fun equals(other: Any?): Boolean =
      other is HouseholdFinanceSnapshot &&
          householdId == other.householdId &&
          expenses == other.expenses &&
          settlements == other.settlements &&
          isSynced == other.isSynced

  override fun hashCode(): Int =
      31 * (31 * (31 * householdId.hashCode() + expenses.hashCode()) + settlements.hashCode()) +
          isSynced.hashCode()

  override fun toString(): String =
      "HouseholdFinanceSnapshot(householdId=$householdId, expenses=$expenses, settlements=$settlements, isSynced=$isSynced)"
}
