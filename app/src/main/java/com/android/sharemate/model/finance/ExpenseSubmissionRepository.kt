// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

/** Expense-only write boundary; inventory and durable offline queueing belong to the caller. */
interface ExpenseSubmissionRepository {
  /**
   * Records an immutable expense under the authenticated submitter. Return means server commit,
   * including an identical replay. Persist the original payload and ID before invoking; this
   * operation does not enqueue offline. Keep queued/pending UI state in the caller until confirmed.
   * [ExpenseRejectedException] means this attempt was conclusively refused; an earlier attempt may
   * already have committed. Other failures (including cancellation or a lost/malformed response)
   * have uncertain outcomes: reconcile or retry the original payload, never mint a replacement ID.
   * The authenticated caller must be the payer; IDs do not grant authorization.
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
