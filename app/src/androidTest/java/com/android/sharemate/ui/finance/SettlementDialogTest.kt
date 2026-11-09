package com.android.sharemate.ui.finance

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
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
  fun validatesExactCentsAndConfirmsFullOrPartialAmount() {
    val amounts = mutableListOf<Long>()
    var dismissals = 0
    compose.setContent {
      MaterialTheme { SettleUpDialog(debt, "Bob", { dismissals++ }, { amounts += it }) }
    }
    compose.onNodeWithText("You owe Bob", substring = true).assertIsDisplayed()
    compose.onNodeWithTag("settlement_confirm").performClick()
    assertEquals(listOf(1500L), amounts)
    for (input in listOf("", "0", "-1", "15.01", "1.001", "6,00", "92233720368547758.08")) {
      compose.onNodeWithTag("settlement_amount").performTextReplacement(input)
      compose.onNodeWithTag("settlement_confirm").assertIsNotEnabled()
    }
    for ((input, expected) in
        listOf("0.01" to 1L, "14.99" to 1499L, "15.00" to 1500L, "6.00" to 600L)) {
      compose.onNodeWithTag("settlement_amount").performTextReplacement(input)
      compose.onNodeWithTag("settlement_confirm").assertIsEnabled().performClick()
      assertEquals(expected, amounts.last())
    }
    compose.onNodeWithText("Cancel").performClick()
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
    compose.onNodeWithText("Settle up").assertDoesNotExist()
    compose.runOnIdle {
      state.value = (state.value as SettlementUiState.Ready).copy(currentUserId = "alice")
    }
    compose.onNodeWithText("Settle up").performClick()
    compose.onNodeWithTag("settlement_confirm").performClick()
    compose.onNodeWithTag("settlement_amount").assertIsDisplayed()
    compose.onNodeWithText("Cancel").performClick()
    compose.runOnIdle {
      state.value =
          (state.value as SettlementUiState.Ready).copy(
              submission = SettlementSubmission(SettlementPhase.FAILED))
    }
    compose.onNodeWithText("Settle up").assertDoesNotExist()
    compose.onNodeWithText("Try again").performClick()
    assertEquals(1, retries)
    for (phase in listOf(SettlementPhase.SUBMITTING, SettlementPhase.AWAITING_CONFIRMATION)) {
      compose.runOnIdle {
        state.value =
            (state.value as SettlementUiState.Ready).copy(submission = SettlementSubmission(phase))
      }
      compose.onNodeWithText("Settle up").assertDoesNotExist()
      compose.onNodeWithText("Recording payment. Waiting for confirmation…").assertIsDisplayed()
    }
    compose.runOnIdle {
      state.value =
          (state.value as SettlementUiState.Ready).copy(
              submission = SettlementSubmission(SettlementPhase.REJECTED))
      accepted = true
    }
    compose.onNodeWithText("Settle up").performClick()
    compose.onNodeWithTag("settlement_confirm").performClick()
    compose.onNodeWithTag("settlement_confirm").assertDoesNotExist()
    for (unavailable in
        listOf(
            SettlementUiState.Loading,
            SettlementUiState.Offline,
            SettlementUiState.Error,
            SettlementUiState.InvalidData,
            SettlementUiState.Unavailable)) {
      compose.runOnIdle { state.value = unavailable }
      compose.onNodeWithTag(BalancesTestTags.SUMMARY).assertDoesNotExist()
      compose.onNodeWithText("Settle up").assertDoesNotExist()
    }
  }
}
