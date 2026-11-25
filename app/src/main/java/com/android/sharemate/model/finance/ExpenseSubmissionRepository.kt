// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

/** Expense-only write boundary; inventory and durable offline queueing belong to the caller. */
interface ExpenseSubmissionRepository {
  /** Deletes the caller's saved receipt remotely; returning means server acknowledgement. */
  suspend fun deleteReceipt(householdId: String, id: String) {
    error("Receipt deletion unavailable")
  }
  /** Atomically updates an existing receipt's inventory and financial allocation. */
  suspend fun correctReceipt(correction: com.android.sharemate.model.staging.ReceiptCorrection) {
    error("Receipt corrections unavailable")
  }
  /**
   * Records an immutable expense under the authenticated submitter. Return means server commit,
   * including an identical replay. Persist the original payload and ID before invoking; this
   * operation does not enqueue offline. Keep queued/pending UI state in the caller until confirmed.
   * [ExpenseRejectedException] means this attempt was conclusively refused; an earlier attempt may
   * already have committed. Other failures (including cancellation or a lost/malformed response)
   * have uncertain outcomes: reconcile or retry the original payload, never mint a replacement ID.
   * Caller, payer and participants must belong to the shared household on creation. Retry ownership
   * remains with the authenticated submitter; selecting another payer does not transfer it.
   */
  suspend fun recordExpense(expense: SharedExpense): SharedExpense
}

/** Definite rejection of this attempt, not proof that an earlier attempt did not commit. */
class ExpenseRejectedException(val reason: ExpenseRejectionReason, cause: Throwable) :
    Exception("Expense rejected: $reason", cause)

/** Recovery category independent of Firebase types. */
enum class ExpenseRejectionReason {
  Conflict,
  PermissionDenied,
  Invalid,
  Unauthenticated,
  FailedPrecondition,
}
