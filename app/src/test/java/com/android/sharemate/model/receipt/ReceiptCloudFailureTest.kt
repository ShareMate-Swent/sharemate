// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.receipt

import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import org.junit.Assert.*
import org.junit.Test

class ReceiptCloudFailureTest {
  @Test
  fun accessAndConfigurationErrorsStopButTemporaryFailuresRemainRetryable() {
    for (code in listOf(Code.PERMISSION_DENIED, Code.UNAUTHENTICATED, Code.FAILED_PRECONDITION)) {
      val failure = FirebaseFirestoreException("Rejected", code)
      assertSame(failure, (receiptCloudFailure(failure) as ReceiptCloudPermanentException).cause)
    }
    for (code in listOf(Code.UNAVAILABLE, Code.DEADLINE_EXCEEDED, Code.RESOURCE_EXHAUSTED)) {
      val failure = FirebaseFirestoreException("Temporary", code)
      assertSame(failure, receiptCloudFailure(failure))
    }
  }
}
