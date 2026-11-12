// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.finance

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.R
import com.android.sharemate.model.finance.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettlementDialogTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val debt = Debt("alice", "bob", 1500)

  @Test
  fun frenchSwissConfigurationDisplaysAndAcceptsDecimalComma() {
    val configuration =
        android.content.res.Configuration(compose.activity.resources.configuration).apply {
          setLocale(java.util.Locale.forLanguageTag("fr-CH"))
        }
    var amount: Long? = null
    compose.setContent {
      androidx.compose.runtime.CompositionLocalProvider(
          androidx.compose.ui.platform.LocalConfiguration provides configuration) {
            MaterialTheme { SettleUpDialog(debt, "Bob", {}, { amount = it }) }
          }
    }
    compose
        .onNodeWithText(
            formatFinanceAmount(debt.amountCents, java.util.Locale.forLanguageTag("fr-CH")),
            substring = true)
        .assertIsDisplayed()
    compose.onNodeWithTag(BalancesTestTags.SETTLEMENT_AMOUNT).performTextReplacement("6,00")
    compose.onNodeWithTag(BalancesTestTags.SETTLEMENT_CONFIRM).assertIsEnabled().performClick()
    assertEquals(600L, amount)
  }

  @Test
  fun validatesExactCentsAndConfirmsFullOrPartialAmount() {
    val amounts = mutableListOf<Long>()
    var dismissals = 0
    compose.setContent {
      MaterialTheme { SettleUpDialog(debt, "Bob", { dismissals++ }, { amounts += it }) }
    }
    compose.onNodeWithText("You owe Bob", substring = true).assertIsDisplayed()
    compose.onNodeWithTag(BalancesTestTags.SETTLEMENT_CONFIRM).performClick()
    assertEquals(listOf(1500L), amounts)
    for (input in
        listOf(
            "",
            "0",
            "-1",
            "15.01",
            "1.001",
            "1,001",
            "1,000.00",
            "1.000,00",
            "1,,00",
            "15,01",
            "0,00",
            "92233720368547758.08")) {
      compose.onNodeWithTag(BalancesTestTags.SETTLEMENT_AMOUNT).performTextReplacement(input)
      compose.onNodeWithTag(BalancesTestTags.SETTLEMENT_CONFIRM).assertIsNotEnabled()
    }
    for ((input, expected) in
        listOf(
            "0.01" to 1L,
            "14.99" to 1499L,
            "15.00" to 1500L,
            "6.00" to 600L,
            "6,00" to 600L,
            "0,01" to 1L,
            "15,00" to 1500L)) {
      compose.onNodeWithTag(BalancesTestTags.SETTLEMENT_AMOUNT).performTextReplacement(input)
      compose.onNodeWithTag(BalancesTestTags.SETTLEMENT_CONFIRM).assertIsEnabled().performClick()
      assertEquals(expected, amounts.last())
    }
    compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
    assertEquals(1, dismissals)
  }

  @Test
  fun onlyThePayerCanOpenDialogAndUncertainPaymentsOfferRetry() {
    var retries = 0
    var accepted = false
    val state =
        androidx.compose.runtime.mutableStateOf<SettlementUiState>(
            SettlementUiState.Ready(
                "bob",
                BalanceSummary("home", mapOf("alice" to -1500L, "bob" to 1500L), listOf(debt)),
                SettlementSubmission()))
    compose.setContent {
      MaterialTheme { SettlementBalances(state.value, {}, { _, _ -> accepted }, { retries++ }) }
    }
    compose
        .onNodeWithText(compose.activity.getString(R.string.settlement_action))
        .assertDoesNotExist()
    compose.runOnIdle {
      state.value = (state.value as SettlementUiState.Ready).copy(currentUserId = "alice")
    }
    compose.onNodeWithText(compose.activity.getString(R.string.settlement_action)).performClick()
    compose.onNodeWithTag(BalancesTestTags.SETTLEMENT_CONFIRM).performClick()
    compose.onNodeWithTag(BalancesTestTags.SETTLEMENT_AMOUNT).assertIsDisplayed()
    compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
    compose.runOnIdle {
      state.value =
          (state.value as SettlementUiState.Ready).copy(
              submission = SettlementSubmission(SettlementPhase.FAILED))
    }
    compose
        .onNodeWithText(compose.activity.getString(R.string.settlement_action))
        .assertDoesNotExist()
    compose.onNodeWithText(compose.activity.getString(R.string.household_retry)).performClick()
    assertEquals(1, retries)
    for (phase in listOf(SettlementPhase.SUBMITTING, SettlementPhase.AWAITING_CONFIRMATION)) {
      compose.runOnIdle {
        state.value =
            (state.value as SettlementUiState.Ready).copy(submission = SettlementSubmission(phase))
      }
      compose
          .onNodeWithText(compose.activity.getString(R.string.settlement_action))
          .assertDoesNotExist()
      compose
          .onNodeWithText(compose.activity.getString(R.string.settlement_pending))
          .assertIsDisplayed()
    }
    compose.runOnIdle {
      state.value =
          (state.value as SettlementUiState.Ready).copy(
              submission = SettlementSubmission(SettlementPhase.REJECTED))
      accepted = true
    }
    compose.onNodeWithText(compose.activity.getString(R.string.settlement_action)).performClick()
    compose.onNodeWithTag(BalancesTestTags.SETTLEMENT_CONFIRM).performClick()
    compose.onNodeWithTag(BalancesTestTags.SETTLEMENT_CONFIRM).assertDoesNotExist()
    compose.runOnIdle {
      state.value = (state.value as SettlementUiState.Ready).copy(isSynced = false)
    }
    compose
        .onNodeWithText(compose.activity.getString(R.string.settlement_cached_balances))
        .assertIsDisplayed()
    compose.runOnIdle {
      state.value = (state.value as SettlementUiState.Ready).copy(isSynced = true)
    }
    compose
        .onNodeWithText(compose.activity.getString(R.string.settlement_cached_balances))
        .assertDoesNotExist()
    for (unavailable in
        listOf(
            SettlementUiState.Loading,
            SettlementUiState.Offline,
            SettlementUiState.Error,
            SettlementUiState.InvalidData,
            SettlementUiState.Unavailable)) {
      compose.runOnIdle { state.value = unavailable }
      compose.onNodeWithTag(BalancesTestTags.SUMMARY).assertDoesNotExist()
      compose
          .onNodeWithText(compose.activity.getString(R.string.settlement_action))
          .assertDoesNotExist()
    }
  }
}
