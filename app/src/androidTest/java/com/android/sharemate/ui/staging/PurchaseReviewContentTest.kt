// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.staging

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.android.sharemate.R
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

  @Test
  fun eachValidationErrorShowsItsActionableMessage() {
    val error = mutableStateOf(PurchaseReviewError.MISSING_PRICE)
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val messages =
        mapOf(
            PurchaseReviewError.MISSING_PRICE to R.string.purchase_missing_price,
            PurchaseReviewError.TOTAL_OVERFLOW to R.string.purchase_overflow,
            PurchaseReviewError.TOTAL_TOO_LARGE to R.string.purchase_overflow,
            PurchaseReviewError.MEMBERSHIP_CHANGED to R.string.purchase_membership_changed,
            PurchaseReviewError.NONPOSITIVE_TOTAL to R.string.purchase_positive_total,
            PurchaseReviewError.INVALID_PAYER to R.string.purchase_invalid_payer,
            PurchaseReviewError.NO_PARTICIPANTS to R.string.purchase_no_participants,
            PurchaseReviewError.INVALID_PARTICIPANTS to R.string.purchase_invalid_participants,
        )
    compose.setContent {
      MaterialTheme {
        PurchaseReviewContent(
            PurchasePreview(1000L, error = error.value),
            PurchaseReview(emptyMap(), null),
            onPayer = {},
            onParticipant = {},
            enabled = true,
        )
      }
    }
    messages.forEach { (value, resource) ->
      compose.runOnIdle { error.value = value }
      compose.onNodeWithTag("staging_purchase_error").assertTextEquals(context.getString(resource))
    }
  }

  @Test
  fun payerSelectionAndBlankNamesUseMemberIds() {
    val vm =
        StagingViewModel(
            listOf(StagedItem("row", "Milk", priceCents = 1000)),
            PurchaseReview.forUser(null, mapOf("a" to " ")),
        )
    var confirmed: ReviewedPurchase? = null
    compose.setContent {
      MaterialTheme {
        StagingScreen(vm, onConfirm = {}, onBack = {}, onConfirmPurchase = { confirmed = it })
      }
    }
    compose.onNodeWithTag("staging_payer_a").performClick()
    compose.onNodeWithTag("staging_participant_a").performClick()
    compose.onNodeWithTag("staging_confirm_purchase").performClick()
    compose.runOnIdle {
      assertEquals("a", confirmed?.paidBy)
      assertEquals(mapOf("a" to 1000L), confirmed?.shares)
    }
  }
}
