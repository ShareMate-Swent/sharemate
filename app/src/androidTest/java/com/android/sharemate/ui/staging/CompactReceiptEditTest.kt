// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.staging

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.staging.StagedItem
import com.android.sharemate.ui.theme.ShareMateTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompactReceiptEditTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()
  private val milk = StagedItem("milk", "Milk", 2, 189)

  @Test
  fun receiptDeletionSurfacesFollowDarkTheme() {
    var deleted = 0
    composeTestRule.setContent {
      ShareMateTheme(darkTheme = true) {
        StagingContent(StagingUiState(), {}, {}, {}, {}, onDeleteReceipt = { deleted++ })
      }
    }
    composeTestRule.onNodeWithTag(StagingTestTags.RECEIPT_MORE).performClick()
    for (tag in listOf(StagingTestTags.RECEIPT_MENU, StagingTestTags.RECEIPT_DELETE_DIALOG)) {
      if (tag == StagingTestTags.RECEIPT_DELETE_DIALOG)
          composeTestRule.onNodeWithTag(StagingTestTags.RECEIPT_DELETE).performClick()
      val image = composeTestRule.onNodeWithTag(tag).assertIsDisplayed().captureToImage()
      val pixels = image.toPixelMap()
      var darkPixels = 0
      for (y in 0 until image.height) {
        for (x in 0 until image.width) {
          if (pixels[x, y].luminance() < 0.5f) darkPixels++
        }
      }
      assertTrue(
          "The $tag surface must follow dark mode", darkPixels > image.width * image.height / 2)
    }
    composeTestRule.onNodeWithText("Cancel").performClick()
    assertEquals(0, deleted)
  }

  @Test
  fun compactReviewCanEditDeleteAndAddItemsWithUpdatedTotals() {
    val vm = StagingViewModel(listOf(milk), PurchaseReview.forUser("a", mapOf("a" to "Alex")))
    composeTestRule.setContent {
      MaterialTheme { StagingScreen(vm, {}, {}, separateSharingStep = true) }
    }
    composeTestRule.onNodeWithTag(StagingTestTags.item("milk")).performClick()
    composeTestRule.onNodeWithTag(StagedItemEditTestTags.NAME).performTextReplacement("Oat milk")
    composeTestRule.onNodeWithTag(StagedItemEditTestTags.PRICE).performTextReplacement("4.50")
    composeTestRule.onNodeWithTag(StagedItemEditTestTags.QUANTITY).performTextReplacement("3")
    composeTestRule.onNodeWithTag(StagedItemEditTestTags.SAVE).performClick()
    composeTestRule.runOnIdle {
      assertEquals(StagedItem("milk", "Oat milk", 3, 450), vm.state.value.items.single())
      assertEquals(450L, vm.state.value.purchasePreview?.totalCents)
    }
    composeTestRule.onNodeWithTag(StagingTestTags.delete("milk")).performClick()
    composeTestRule.runOnIdle { assertEquals(0, vm.state.value.items.size) }
    composeTestRule.onNodeWithTag(StagingTestTags.ADD).performClick()
    composeTestRule.onNodeWithTag(StagedItemEditTestTags.NAME).performTextReplacement("Apples")
    composeTestRule.onNodeWithTag(StagedItemEditTestTags.PRICE).performTextReplacement("2.10")
    composeTestRule.onNodeWithTag(StagedItemEditTestTags.SAVE).performClick()
    composeTestRule.runOnIdle {
      assertEquals("Apples", vm.state.value.items.single().name)
      assertEquals(210L, vm.state.value.purchasePreview?.totalCents)
    }
  }
}
