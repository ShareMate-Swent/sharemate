// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.receipt

import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import com.google.firebase.storage.StorageException
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

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
    for (code in
        listOf(
            StorageException.ERROR_NOT_AUTHORIZED,
            StorageException.ERROR_OBJECT_NOT_FOUND,
            StorageException.ERROR_RETRY_LIMIT_EXCEEDED)) {
      val failure = mock(StorageException::class.java)
      `when`(failure.errorCode).thenReturn(code)
      assertEquals(
          code != StorageException.ERROR_RETRY_LIMIT_EXCEEDED,
          receiptCloudFailure(failure) is ReceiptCloudPermanentException)
    }
    val offline = IOException("Offline")
    assertSame(offline, receiptCloudFailure(offline))
  }
}
