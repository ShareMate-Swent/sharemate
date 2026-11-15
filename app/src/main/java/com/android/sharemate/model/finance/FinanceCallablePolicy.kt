// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import com.google.firebase.functions.FirebaseFunctionsException.Code

/** Shared callable region and conclusive rejection classification. */
internal object FinanceCallablePolicy {
  const val REGION = "europe-west6"

  fun rejectionReason(code: Code): ExpenseRejectionReason? =
      when (code) {
        Code.ALREADY_EXISTS -> ExpenseRejectionReason.Conflict
        Code.PERMISSION_DENIED -> ExpenseRejectionReason.PermissionDenied
        Code.INVALID_ARGUMENT -> ExpenseRejectionReason.Invalid
        Code.UNAUTHENTICATED -> ExpenseRejectionReason.Unauthenticated
        Code.FAILED_PRECONDITION -> ExpenseRejectionReason.FailedPrecondition
        else -> null
      }
}
