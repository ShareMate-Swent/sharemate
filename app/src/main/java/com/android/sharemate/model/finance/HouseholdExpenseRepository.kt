// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import kotlinx.coroutines.flow.Flow

/** Read-only storage boundary for shared expenses, before settlement-ledger integration. */
interface HouseholdExpenseRepository {
  /**
   * Observes complete expense lists for a nonblank household document ID under a read-only data
   * source. Each emission represents one consistent query snapshot, including an empty list for an
   * empty collection. Snapshots reported as cached or pending by the SDK are skipped. Invalid
   * records and observation failures terminate collection with an error, never a partial or empty
   * fallback. Cancellation releases listeners. Membership authorization belongs to the trusted
   * backend; [householdId] is not proof of access. Historical payers and share participants remain
   * in records.
   *
   * This expense-only boundary does not represent a complete ledger with settlements. No client
   * producer is enabled yet; expense writing requires an agreed allocation contract and trusted
   * authorization. Producer integration must revisit optimistic-write handling: SDK metadata alone
   * cannot certify the absence of external pending deletions. Member names must not be obtained by
   * exposing private user profiles.
   */
  fun observeHouseholdExpenses(householdId: String): Flow<List<SharedExpense>>
}
