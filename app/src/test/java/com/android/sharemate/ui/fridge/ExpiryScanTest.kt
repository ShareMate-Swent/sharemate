// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExpiryScanTest {
  @get:Rule val compose = createComposeRule()
  private val confirmed = mutableListOf<String>()

  @Test
  fun onlyTheSelectedCandidateIsPassedToTheForm() {
    showCandidates(listOf(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 10, 12)))

    compose.onNodeWithText("2026-01-01").assertIsDisplayed()
    compose.runOnIdle { assertEquals(emptyList<String>(), confirmed) }
    compose.onNodeWithText("2027-10-12").performClick()

    compose.runOnIdle { assertEquals(listOf("2027-10-12"), confirmed) }
    compose.onNodeWithText("Choose the expiry date").assertDoesNotExist()
  }

  @Test
  fun noDateAllowsManualEntryWithoutReplacingTheFormValue() {
    showCandidates(emptyList())

    compose.onNodeWithText("No date found. You can still enter it manually.").assertIsDisplayed()
    compose.onNodeWithText("Enter manually").performClick()

    compose.runOnIdle { assertEquals(emptyList<String>(), confirmed) }
    compose.onNodeWithText("Choose the expiry date").assertDoesNotExist()
  }

  private fun showCandidates(dates: List<LocalDate>) {
    val scan = ExpiryScanViewModel { dates }
    compose.setContent {
      LaunchedEffect(scan) { scan.scan("file:///private/expiry.jpg") }
      MaterialTheme {
        ExpiryScan(enabled = true, onDateConfirmed = { confirmed.add(it) }, scan = scan)
      }
    }
  }
}
