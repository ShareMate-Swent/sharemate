// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.android.sharemate.R
import com.android.sharemate.ui.theme.ShareMateTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Checks the sheet of the add button: its options, what a tap reports and its Cancel button.
 *
 * The sheet has its own window, which Robolectric does not send touches to, so taps run the click
 * action of the node; `AddItemSheetFlowTest` taps it on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp")
class AddItemSheetTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val chosen = mutableListOf<AddItemMethod>()
  private var dismissed = 0

  private fun showSheet() =
      compose.setContent {
        ShareMateTheme { AddItemSheet(onChoose = { chosen += it }, onDismiss = { dismissed++ }) }
      }

  @Test
  fun sheetOffersBarcodeManualAndReceiptInOrderWithTheirTexts() {
    showSheet()
    compose.onNodeWithText(compose.activity.getString(R.string.nav_add_item)).assertIsDisplayed()
    compose
        .onNodeWithText(compose.activity.getString(R.string.add_item_sheet_description))
        .assertIsDisplayed()
    val texts =
        mapOf(
            AddItemMethod.BARCODE to
                (R.string.add_item_barcode to R.string.add_item_barcode_description),
            AddItemMethod.MANUAL to
                (R.string.add_item_manual to R.string.add_item_manual_description),
            AddItemMethod.RECEIPT to
                (R.string.add_item_receipt to R.string.add_item_receipt_description))
    texts.values.forEach { (title, description) ->
      compose.onNodeWithText(compose.activity.getString(title)).assertIsDisplayed()
      compose.onNodeWithText(compose.activity.getString(description)).assertIsDisplayed()
    }
    val tops =
        AddItemMethod.entries.map {
          compose.onNodeWithTag(AddItemSheetTestTags.option(it)).getUnclippedBoundsInRoot().top
        }
    assertEquals(tops.sortedBy { it.value }, tops)
  }

  @Test
  fun tappingAnOptionReportsItsMethod() {
    showSheet()
    compose.onNodeWithTag(AddItemSheetTestTags.option(AddItemMethod.MANUAL)).tap()
    compose.runOnIdle {
      assertEquals(listOf(AddItemMethod.MANUAL), chosen)
      assertEquals(0, dismissed)
    }
  }

  @Test
  fun cancelClosesTheSheetWithoutAChoice() {
    showSheet()
    compose.onNodeWithTag(AddItemSheetTestTags.CANCEL).tap()
    compose.runOnIdle {
      assertEquals(1, dismissed)
      assertEquals(emptyList<AddItemMethod>(), chosen)
    }
  }

  private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)
}
