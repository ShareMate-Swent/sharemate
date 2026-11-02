// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import java.util.Collections

/**
 * Immutable output of [BalanceCalculator], in CHF cents. Positive nets are credit; negative nets
 * are amounts owed. Net balances sum to zero mathematically, even when intermediate Long sums would
 * overflow. Debts reproduce those nets; current and historical users with zero nets remain in
 * [netBalancesCents] without creating debt entries.
 */
class BalanceSummary
internal constructor(
    val householdId: String,
    netBalancesCents: Map<String, Long>,
    debts: List<Debt>,
) {
  val netBalancesCents: Map<String, Long> =
      Collections.unmodifiableMap(LinkedHashMap(netBalancesCents))
  val debts: List<Debt> = Collections.unmodifiableList(ArrayList(debts))

  override fun equals(other: Any?): Boolean =
      other is BalanceSummary &&
          householdId == other.householdId &&
          netBalancesCents == other.netBalancesCents &&
          debts == other.debts

  override fun hashCode(): Int =
      31 * (31 * householdId.hashCode() + netBalancesCents.hashCode()) + debts.hashCode()

  override fun toString(): String =
      "BalanceSummary(householdId=$householdId, netBalancesCents=$netBalancesCents, debts=$debts)"
}
