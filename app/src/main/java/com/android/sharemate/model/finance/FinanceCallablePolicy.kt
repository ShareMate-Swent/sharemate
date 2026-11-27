// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.finance

import com.android.sharemate.model.CloudFunctions
import com.google.firebase.functions.FirebaseFunctionsException.Code

/** Shared callable region and conclusive rejection classification. */
internal object FinanceCallablePolicy {
  const val REGION = CloudFunctions.REGION

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
