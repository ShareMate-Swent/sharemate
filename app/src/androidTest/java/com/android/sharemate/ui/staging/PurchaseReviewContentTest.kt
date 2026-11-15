// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.staging

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.android.sharemate.model.staging.StagedItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PurchaseReviewContentTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun payerAndParticipantControlsUpdatePreview() {
    val vm =
        StagingViewModel(
            listOf(StagedItem("row", "Milk", priceCents = 1000)),
            PurchaseReview.forUser("a", mapOf("a" to "Alex", "b" to "Blair")),
        )
    var confirmed: ReviewedPurchase? = null
    compose.setContent {
      MaterialTheme {
        StagingScreen(vm, onConfirm = {}, onBack = {}, onConfirmPurchase = { confirmed = it })
      }
    }
    compose.onNodeWithTag("staging_confirm_purchase").assertIsNotEnabled()
    compose.onNodeWithTag("staging_participant_b").performClick()
    compose.onNodeWithTag("staging_share_b").assertIsDisplayed()
    compose.onNodeWithTag("staging_confirm_purchase").performClick()
    compose.runOnIdle {
      assertEquals("a", confirmed?.paidBy)
      assertEquals(mapOf("b" to 1000L), confirmed?.shares)
    }
  }

  @Test
  fun disabledControlsCannotChangeSelection() {
    compose.setContent {
      MaterialTheme {
        PurchaseReviewContent(
            null,
            PurchaseReview(mapOf("a" to "Alex"), "a"),
            onPayer = { error("disabled") },
            onParticipant = { error("disabled") },
            enabled = false,
        )
      }
    }
    compose.onNodeWithTag("staging_payer_a").assertIsNotEnabled()
    compose.onNodeWithTag("staging_participant_a").assertIsNotEnabled()
  }
}
