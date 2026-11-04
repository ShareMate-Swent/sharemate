// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.staging

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.staging.StagedItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tap a row, edit it in the dialog, then save or cancel, through the real ViewModel. */
@RunWith(AndroidJUnit4::class)
class StagingEditFlowTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val milk = StagedItem("milk", "Milk", quantity = 2, priceCents = 189)
  private val bread = StagedItem("bread", "Bread")
  private var confirmed: List<StagedItem>? = null

  private fun show(viewModel: StagingViewModel) {
    compose.setContent {
      MaterialTheme { StagingScreen(viewModel, onConfirm = { confirmed = it }, onBack = {}) }
    }
  }

  private fun assertRowShows(id: String, text: String) {
    compose
        .onNode(
            hasText(text) and hasAnyAncestor(hasTestTag(StagingTestTags.item(id))),
            useUnmergedTree = true)
        .assertIsDisplayed()
  }

  @Test
  fun tappingARowOpensTheDialogWithItsValues() {
    val viewModel = StagingViewModel(listOf(milk, bread))
    show(viewModel)
    compose.onNodeWithTag(StagedItemEditTestTags.DIALOG).assertDoesNotExist()

    compose.onNodeWithTag(StagingTestTags.item("milk")).performClick()

    compose.onNodeWithTag(StagedItemEditTestTags.DIALOG).assertIsDisplayed()
    assertEquals(StagedItemDraft("milk", "Milk", "2", "1.89"), viewModel.state.value.draft)
    compose.onNodeWithTag(StagingTestTags.CONFIRM).assertIsNotEnabled()
  }

  @Test
  fun savingTheDialogUpdatesTheRow() {
    show(StagingViewModel(listOf(milk, bread)))
    compose.onNodeWithTag(StagingTestTags.item("bread")).performClick()

    compose.onNodeWithTag(StagedItemEditTestTags.NAME).performTextReplacement("Baguette")
    compose.onNodeWithTag(StagedItemEditTestTags.QUANTITY).performTextReplacement("3")
    compose.onNodeWithTag(StagedItemEditTestTags.PRICE).performTextReplacement("2,40")
    compose.onNodeWithTag(StagedItemEditTestTags.SAVE).performClick()

    compose.onNodeWithTag(StagedItemEditTestTags.DIALOG).assertDoesNotExist()
    assertRowShows("bread", "Baguette")
    assertRowShows("bread", "Qty 3")
    assertRowShows("bread", "CHF 2.40")
    compose.onNodeWithTag(StagingTestTags.CONFIRM).assertIsEnabled().performClick()
    assertEquals(listOf(milk, StagedItem("bread", "Baguette", 3, 240)), confirmed)
  }

  @Test
  fun invalidValuesKeepTheDialogOpenWithAnError() {
    show(StagingViewModel(listOf(milk)))
    compose.onNodeWithTag(StagingTestTags.item("milk")).performClick()

    compose.onNodeWithTag(StagedItemEditTestTags.PRICE).performTextReplacement("abc")
    compose.onNodeWithTag(StagedItemEditTestTags.SAVE).performClick()

    compose.onNodeWithTag(StagedItemEditTestTags.DIALOG).assertIsDisplayed()
    compose.onNodeWithTag(StagedItemEditTestTags.ERROR).assertIsDisplayed()

    compose.onNodeWithTag(StagedItemEditTestTags.PRICE).performTextReplacement("")
    compose.onNodeWithTag(StagedItemEditTestTags.ERROR).assertDoesNotExist()
    compose.onNodeWithTag(StagedItemEditTestTags.SAVE).performClick()
    assertRowShows("milk", "No price")
  }

  @Test
  fun cancelClosesTheDialogWithoutChanges() {
    show(StagingViewModel(listOf(milk)))
    compose.onNodeWithTag(StagingTestTags.item("milk")).performClick()

    compose.onNodeWithTag(StagedItemEditTestTags.NAME).performTextReplacement("Juice")
    compose.onNodeWithTag(StagedItemEditTestTags.CANCEL).performClick()

    compose.onNodeWithTag(StagedItemEditTestTags.DIALOG).assertDoesNotExist()
    assertRowShows("milk", "Milk")
    compose.onNodeWithTag(StagingTestTags.CONFIRM).assertIsEnabled()
  }
}
