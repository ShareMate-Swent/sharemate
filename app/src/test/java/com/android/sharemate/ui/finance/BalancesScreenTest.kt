// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.finance

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.android.sharemate.R
import com.android.sharemate.model.finance.BalanceCalculator
import com.android.sharemate.model.finance.HouseholdFinanceSnapshot
import com.android.sharemate.model.finance.SharedExpense
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-rUS")
class BalancesScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private var state by mutableStateOf<BalancesUiState>(BalancesUiState.Loading)
  private var retries = 0

  private fun show() {
    compose.setContent { MaterialTheme { BalancesScreen(state, { retries++ }) } }
  }

  @Test
  fun loadingErrorRetryAndUnavailableNeverShowAnOldSummary() {
    show()
    compose.onNodeWithTag(BalancesTestTags.LOADING).assertIsDisplayed()
    compose.onNodeWithTag(BalancesTestTags.SUMMARY).assertDoesNotExist()
    compose.runOnIdle { state = BalancesUiState.Error }
    compose.onNodeWithTag(BalancesTestTags.LOADING).assertDoesNotExist()
    compose.onNodeWithText(compose.activity.getString(R.string.balances_error)).assertIsDisplayed()
    compose.onNodeWithTag(BalancesTestTags.RETRY).performClick()
    assertEquals(1, retries)
    compose.runOnIdle { state = BalancesUiState.Unavailable }
    compose
        .onNodeWithText(compose.activity.getString(R.string.balances_unavailable))
        .assertIsDisplayed()
    compose.onNodeWithTag(BalancesTestTags.RETRY).assertDoesNotExist()
    compose.onNodeWithTag(BalancesTestTags.SUMMARY).assertDoesNotExist()
  }

  @Test
  fun showsOwnSignedBalanceAndBothDebtDirectionsWithUnambiguousMemberLabels() {
    val summary =
        BalanceCalculator.calculate(
            HouseholdFinanceSnapshot(
                "home",
                listOf(SharedExpense("one", "home", "alice", 1500, mapOf("bob" to 1500L))),
                emptyList()),
            listOf("alice", "bob"))
    state = BalancesUiState.Ready("bob", summary, mapOf("alice" to "Alice"))
    show()
    compose.onNodeWithTag(BalancesTestTags.SUMMARY).assertTextContains("15.00", substring = true)
    compose.onNodeWithText("You owe Alice", substring = true).assertIsDisplayed()
    compose.runOnIdle { state = BalancesUiState.Ready("alice", summary, mapOf("bob" to "Bob")) }
    compose.onNodeWithTag(BalancesTestTags.SUMMARY).assertTextContains("+", substring = true)
    compose.onNodeWithText("Bob owes You", substring = true).assertIsDisplayed()
    compose.runOnIdle { state = BalancesUiState.Error }
    compose.onNodeWithTag(BalancesTestTags.SUMMARY).assertDoesNotExist()
    compose.onNodeWithText("Bob owes You", substring = true).assertDoesNotExist()
  }

  @Test
  fun emptyHouseholdShowsZeroAndNoOutstandingDebts() {
    state =
        BalancesUiState.Ready(
            "alice",
            BalanceCalculator.calculate(
                HouseholdFinanceSnapshot("home", emptyList(), emptyList()), listOf("alice")))
    show()
    compose.onNodeWithTag(BalancesTestTags.SUMMARY).assertTextContains("0.00", substring = true)
    compose.onNodeWithText(compose.activity.getString(R.string.balances_empty)).assertIsDisplayed()
  }

  @Test
  fun missingMemberNameUsesAGenericLabelWithoutExposingTheId() {
    state =
        BalancesUiState.Ready(
            "alice",
            BalanceCalculator.calculate(
                HouseholdFinanceSnapshot(
                    "home",
                    listOf(
                        SharedExpense(
                            "e", "home", "opaque-user-id", 1500, mapOf("alice" to 1500L))),
                    emptyList()),
                listOf("alice", "opaque-user-id")))
    show()
    compose.onNodeWithText("You owe Household member", substring = true).assertIsDisplayed()
    compose.onNodeWithText("opaque-user-id", substring = true).assertDoesNotExist()
    compose.runOnIdle { state = BalancesUiState.Offline }
    compose.onNodeWithTag(BalancesTestTags.RETRY).assertIsDisplayed()
    compose.onNodeWithTag(BalancesTestTags.SUMMARY).assertDoesNotExist()
    compose.runOnIdle { state = BalancesUiState.InvalidData }
    compose.onNodeWithTag(BalancesTestTags.RETRY).assertDoesNotExist()
  }

  @Test
  fun integerCentFormattingPreservesLongLimitsAndUsesLocale() {
    val minimum = formatFinanceAmount(Long.MIN_VALUE, Locale.US)
    assertTrue(minimum.contains("-"))
    assertTrue(minimum.endsWith("92,233,720,368,547,758.08"))
    assertTrue(formatFinanceAmount(Long.MAX_VALUE, Locale.US).endsWith("92,233,720,368,547,758.07"))
    assertTrue(formatFinanceAmount(1, Locale.US).endsWith("0.01"))
    assertTrue(formatFinanceAmount(1500, Locale.FRANCE).contains("15,00"))
  }
}
