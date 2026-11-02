// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import java.math.BigInteger

/** Calculates expense balances from explicit shares; allocation and rounding happen upstream. */
object BalanceCalculator {
  /**
   * Positive nets are credit and negative nets are amounts owed, in CHF cents. Includes current
   * members with zero activity and all historical payers/share participants. Duplicate current
   * member IDs have no additional effect. Nets and debtor/creditor matching use lexical ID order,
   * independent of input iteration order. Greedy matching does not guarantee minimum debt edges.
   *
   * @throws IllegalArgumentException for blank current member IDs or nonempty settlements, whose
   *   balance effects are not supported yet. Settlements are never silently ignored.
   * @throws ArithmeticException when a final net or debt cannot fit in Long cents. Intermediate
   *   totals use BigInteger, so temporary overflow cannot reject otherwise representable results.
   */
  fun calculate(
      snapshot: HouseholdFinanceSnapshot,
      currentMemberIds: Collection<String>,
  ): BalanceSummary {
    require(snapshot.settlements.isEmpty()) { "Settlement calculation is not supported yet" }
    require(currentMemberIds.all { it.isNotBlank() }) { "Current member IDs must not be blank" }
    val nets = sortedMapOf<String, BigInteger>()
    currentMemberIds.forEach { nets[it] = BigInteger.ZERO }
    snapshot.expenses.forEach { expense ->
      nets[expense.paidBy] =
          (nets[expense.paidBy] ?: BigInteger.ZERO) + BigInteger.valueOf(expense.totalAmountCents)
      expense.shares.forEach { (userId, share) ->
        nets[userId] = (nets[userId] ?: BigInteger.ZERO) - BigInteger.valueOf(share)
      }
    }
    val netCents = nets.mapValues { (_, amount) -> exactCents(amount) }
    val debtors = nets.filterValues { it.signum() < 0 }.map { it.key to it.value.negate() }
    val creditors = nets.filterValues { it.signum() > 0 }.map { it.key to it.value }
    val debts = mutableListOf<Debt>()
    var debtorIndex = 0
    var creditorIndex = 0
    var owed = debtors.firstOrNull()?.second ?: BigInteger.ZERO
    var credit = creditors.firstOrNull()?.second ?: BigInteger.ZERO
    while (debtorIndex < debtors.size && creditorIndex < creditors.size) {
      val amount = owed.min(credit)
      debts += Debt(debtors[debtorIndex].first, creditors[creditorIndex].first, exactCents(amount))
      owed -= amount
      credit -= amount
      if (owed.signum() == 0) {
        debtorIndex++
        owed = debtors.getOrNull(debtorIndex)?.second ?: BigInteger.ZERO
      }
      if (credit.signum() == 0) {
        creditorIndex++
        credit = creditors.getOrNull(creditorIndex)?.second ?: BigInteger.ZERO
      }
    }
    return BalanceSummary(snapshot.householdId, netCents, debts)
  }

  private fun exactCents(amount: BigInteger): Long {
    if (amount < BigInteger.valueOf(Long.MIN_VALUE) ||
        amount > BigInteger.valueOf(Long.MAX_VALUE)) {
      throw ArithmeticException("Balance amount exceeds Long cents")
    }
    return amount.toLong()
  }
}
