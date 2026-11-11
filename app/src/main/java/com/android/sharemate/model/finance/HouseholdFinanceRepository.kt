// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import kotlinx.coroutines.flow.Flow

/** Storage boundary for the financial ledger shared by balance and settlement features. */
interface HouseholdFinanceRepository : SettlementRepository {
  /**
   * Observes expenses and settlements synchronized at the listener boundary for one non-blank
   * [householdId]. Each emission must represent one consistent ledger revision, never independently
   * combined expense/settlement snapshots that could double-count a reimbursement. Cached/pending
   * ledgers emit with [HouseholdFinanceSnapshot.isSynced] false; an empty cache is unknown, not
   * proof of an empty server ledger. Server-confirmed empty ledgers emit a synced empty snapshot.
   * Observation failures throw from collection, never masquerade as an empty ledger; collectors may
   * retry. Cancellation stops observation and releases listeners. Historical records remain
   * available when a participant leaves. Implementations must enforce access using authenticated
   * identity and trusted household membership, not this caller ID.
   */
  fun observeHouseholdFinances(householdId: String): Flow<HouseholdFinanceSnapshot>
}

/**
 * A trusted backend has conclusively refused this operation without committing it. Adapters must
 * not translate timeouts, cancellation or ambiguous transport failures into this exception: those
 * require retrying the original operation identity.
 */
class SettlementRejectedException : Exception("Settlement rejected")
