// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import kotlinx.coroutines.flow.Flow

/** Storage boundary for the financial ledger shared by balance and settlement features. */
interface HouseholdFinanceRepository {
  /**
   * Observes coherent committed expenses and settlements for one non-blank [householdId]. Each
   * emission must represent one consistent ledger revision, never independently combined
   * expense/settlement snapshots that could double-count a reimbursement. Empty ledgers emit an
   * empty snapshot. Observation failures throw from collection, never masquerade as an empty
   * ledger; collectors may retry. Cancellation stops observation and releases listeners. Historical
   * records remain available when a participant leaves. Implementations must enforce access using
   * authenticated identity and trusted household membership, not this caller ID.
   */
  fun observeHouseholdFinances(householdId: String): Flow<HouseholdFinanceSnapshot>

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

/**
 * A trusted backend has conclusively refused this operation without committing it. Adapters must
 * not translate timeouts, cancellation or ambiguous transport failures into this exception: those
 * require retrying the original operation identity.
 */
class SettlementRejectedException : Exception("Settlement rejected")
