// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import kotlinx.coroutines.flow.Flow

/** Read-only expense boundary; the complete ledger also includes settlements. */
interface HouseholdExpenseRepository {
  /**
   * Observes complete expense lists for a nonblank household document ID under a read-only data
   * source. Snapshots reported as cached or pending by the SDK are skipped, so the flow may stay
   * silent until it receives a server snapshot, including while offline. Each emission represents
   * one complete query snapshot. An empty list means the server confirmed there are no expenses.
   *
   * Invalid records and observation failures terminate collection with an error. A complete
   * snapshot already queued may still be delivered before that error. Consumers should clear
   * displayed expenses when handling permission or invalid-data errors. Cancellation releases
   * listeners. Membership authorization belongs to the trusted backend; [householdId] is not proof
   * of access. Historical payers and share participants remain in records.
   *
   * This expense-only boundary does not represent a complete ledger with settlements. Writes use
   * [ExpenseSubmissionRepository]'s authenticated callable; direct client writes remain denied. SDK
   * metadata alone cannot certify the absence of external pending deletions. Member names must not
   * be obtained by exposing private user profiles.
   */
  fun observeHouseholdExpenses(householdId: String): Flow<List<SharedExpense>>
}
