// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

/** Authoritative write boundary for reimbursements. */
interface SettlementRepository {
  /**
   * Records a reimbursement once, using [Settlement.id] as its idempotency key within its
   * household. Repeating the same key and payload returns the original record without another
   * ledger entry; reusing a key with different fields must fail without modifying the original.
   * Concurrent retries must have the same guarantee. Callers must retain the ID and payload before
   * submission and reuse them after an uncertain outcome, including process recreation and
   * cancellation.
   *
   * Return only after authoritative server acknowledgement or reconciliation proves the record
   * committed; local queuing alone is not success. Write errors throw, and cancellation propagates.
   * Either may leave the outcome uncertain, so retry with the same identity rather than a new ID. A
   * successful write must appear exactly once in subsequent committed ledger observations.
   *
   * Implementations must atomically persist the record and any derived ledger updates, validate
   * current household membership and payment constraints on a trusted backend, and authorize the
   * payer from authenticated identity. Never trust caller-supplied IDs as proof of authorization.
   * This contract does not initiate a bank transfer or permit writing another user's payment.
   */
  suspend fun recordSettlement(settlement: Settlement): Settlement
}
