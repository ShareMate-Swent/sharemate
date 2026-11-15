// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.utils.FirebaseEmulator
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies real SDK acknowledgement types, exact cents and idempotent retries. */
@RunWith(AndroidJUnit4::class)
class ExpenseSubmissionEmulatorTest {
  @Before fun clearBefore() = FirebaseEmulator.clear()

  @After fun clearAfter() = FirebaseEmulator.clear()

  @Test
  fun expenseRoundTripAndReplayPreservePayload() = runBlocking {
    withTimeout(60_000) {
      val payer = FirebaseEmulator.signInAs("expense-payer")
      seedHousehold(payer)
      val functions = FirebaseFunctions.getInstance(FirebaseExpenseSubmissionRepository.REGION)
      functions.useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.FUNCTIONS_PORT)
      val repository = FirebaseExpenseSubmissionRepository(functions)
      val expense =
          SharedExpense(
              "sdk-retry",
              "expense-sdk",
              payer,
              100_000_000L,
              mapOf(payer to 0L, "bob" to 100_000_000L))
      assertEquals(expense, repository.recordExpense(expense))
      assertEquals(expense, repository.recordExpense(expense))
    }
  }
  private fun seedHousehold(payer: String) {
    val project = FirebaseApp.getInstance().options.projectId
    val connection =
        URL("http://${FirebaseEmulator.HOST}:${FirebaseEmulator.FIRESTORE_PORT}/v1/projects/$project/databases/(default)/documents/households/expense-sdk")
            .openConnection() as HttpURLConnection
    try {
      connection.connectTimeout = 10_000
      connection.readTimeout = 10_000
      connection.requestMethod = "PATCH"
      connection.setRequestProperty("Authorization", "Bearer owner")
      connection.setRequestProperty("Content-Type", "application/json")
      connection.doOutput = true
      val body = """{"fields":{"type":{"stringValue":"SHARED"},"memberIds":{"arrayValue":{"values":[{"stringValue":"$payer"},{"stringValue":"bob"}]}}}}"""
      connection.outputStream.use { it.write(body.toByteArray()) }
      assertEquals(200, connection.responseCode)
    } finally {
      connection.disconnect()
    }
  }

}
