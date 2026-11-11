// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.finance

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.sharemate.model.auth.FirebaseAuthRepository
import com.android.sharemate.model.auth.FirestorePseudoRepository
import com.android.sharemate.model.finance.FirebaseSettlementRepository
import com.android.sharemate.model.finance.FirestoreHouseholdFinanceRepository
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.model.profile.UserProfile
import com.android.sharemate.model.profile.UserProfileRepository
import com.android.sharemate.ui.theme.SampleAppTheme
import com.android.sharemate.utils.FirebaseEmulator
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production dialog, ViewModel, ledger observer and trusted settlement callable. */
@RunWith(AndroidJUnit4::class)
class SettlementFlowEmulatorTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val firestore = FirebaseEmulator.firestore

  @Before fun clearBefore() = FirebaseEmulator.clear()

  @After
  fun clearAfter() {
    compose.runOnIdle { compose.activity.viewModelStore.clear() }
    FirebaseEmulator.clear()
  }

  @Test
  fun partialThenFullPaymentReducesDisplayedDebtWithoutDoubleCounting() {
    val payer = FirebaseEmulator.signInAs("settlement-payer")
    val home = Household(id = "settlement-ui", memberIds = listOf(payer, "bob"))
    seed(
        "households/${home.id}",
        """{"id":{"stringValue":"${home.id}"},"name":{"stringValue":"Test home"},"type":{"stringValue":"SHARED"},"createdBy":{"stringValue":"$payer"},"memberIds":{"arrayValue":{"values":[{"stringValue":"$payer"},{"stringValue":"bob"}]}}}""")
    seed(
        "households/${home.id}/expenses/e",
        """{"id":{"stringValue":"e"},"householdId":{"stringValue":"${home.id}"},"paidBy":{"stringValue":"bob"},"totalAmountCents":{"integerValue":"1500"},"shares":{"mapValue":{"fields":{"$payer":{"integerValue":"1500"}}}}}""")
    val functions = FirebaseFunctions.getInstance(FirebaseSettlementRepository.REGION)
    functions.useEmulator(FirebaseEmulator.HOST, 5001)
    val finances =
        FirestoreHouseholdFinanceRepository(firestore, FirebaseSettlementRepository(functions))
    val profiles =
        object : UserProfileRepository {
          override suspend fun savePublicProfile(profile: UserProfile) = error("unused")

          override fun observeMembers(householdId: String) =
              flowOf(listOf(UserProfile("bob", "Bob")))
        }
    compose.setContent {
      SampleAppTheme(dynamicColor = false) {
        BalancesRoute(
            finances,
            HouseholdRepositoryFirestore(firestore),
            FirebaseAuthRepository(FirebaseEmulator.auth, FirestorePseudoRepository(firestore)),
            home,
            payer,
            profiles,
            {})
      }
    }
    awaitText("You owe Bob", substring = true)
    compose.onNodeWithTag(BalancesTestTags.SUMMARY).assertTextContains("15.00", substring = true)
    compose.onNodeWithText("Settle up").performClick()
    compose.onNodeWithTag("settlement_amount").assertTextContains("15.00", substring = true)
    compose
        .onNode(hasText("You owe Bob", substring = true) and hasAnyAncestor(isDialog()))
        .assertExists()
    screenshot("settlement-dialog", dialog = true)
    compose.onNodeWithTag("settlement_amount").performTextReplacement("6.00")
    compose.onNodeWithTag("settlement_confirm").performClick()
    try {
      compose.waitUntil(SERVER_TIMEOUT_MS) {
        compose.onAllNodesWithText("Settle up").fetchSemanticsNodes().isNotEmpty() &&
            compose
                .onAllNodes(
                    hasTestTag(BalancesTestTags.SUMMARY) and hasText("9.00", substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty()
      }
    } catch (failure: ComposeTimeoutException) {
      throw AssertionError(compose.onRoot().printToString(), failure)
    }
    compose.onNodeWithTag(BalancesTestTags.SUMMARY).assertTextContains("9.00", substring = true)
    screenshot("settlement-partial")
    compose.onNodeWithText("Settle up").performClick()
    compose.onNodeWithTag("settlement_amount").assertTextContains("9.00", substring = true)
    compose.onNodeWithTag("settlement_confirm").performClick()
    awaitText("No outstanding debts.")
    compose.waitUntil(SERVER_TIMEOUT_MS) {
      compose
          .onAllNodesWithText("Recording payment. Waiting for confirmation…")
          .fetchSemanticsNodes()
          .isEmpty()
    }
    compose.onNodeWithTag(BalancesTestTags.SUMMARY).assertTextContains("0.00", substring = true)
    compose.onNodeWithText("Settle up").assertDoesNotExist()
    screenshot("settlement-complete")
  }

  private fun awaitText(text: String, substring: Boolean = false) {
    compose.waitUntil(SERVER_TIMEOUT_MS) {
      compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun seed(path: String, fields: String) {
    val project = FirebaseApp.getInstance().options.projectId
    val connection =
        URL(
                "http://${FirebaseEmulator.HOST}:8080/v1/projects/$project/databases/(default)/documents/$path")
            .openConnection() as HttpURLConnection
    try {
      connection.connectTimeout = 10_000
      connection.readTimeout = 10_000
      connection.requestMethod = "PATCH"
      connection.setRequestProperty("Authorization", "Bearer owner")
      connection.setRequestProperty("Content-Type", "application/json")
      connection.doOutput = true
      connection.outputStream.use { it.write("""{"fields":$fields}""".toByteArray()) }
      assertEquals(200, connection.responseCode)
    } finally {
      connection.disconnect()
    }
  }

  private fun screenshot(name: String, dialog: Boolean = false) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val bitmap =
        (if (dialog) compose.onNode(isDialog()) else compose.onRoot())
            .captureToImage()
            .asAndroidBitmap()
    File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }

  private companion object {
    // The first callable invocation may need to start the emulator worker.
    const val SERVER_TIMEOUT_MS = 60_000L
  }
}
