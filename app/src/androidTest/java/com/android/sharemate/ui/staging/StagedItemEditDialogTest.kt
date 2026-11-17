// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.staging

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.R
import com.android.sharemate.model.item.ItemQuantity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StagedItemEditDialogTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private var draft by mutableStateOf(StagedItemDraft("milk", "Milk", "2", "1.89"))
  private val calls = mutableListOf<String>()

  private fun setDialog() {
    composeTestRule.setContent {
      MaterialTheme {
        StagedItemEditDialog(
            draft = draft,
            // Apply each change like the ViewModel does: a text field whose value is never
            // updated reports its old value again on a real device.
            onNameChange = {
              draft = draft.copy(name = it)
              calls += "name:$it"
            },
            onQuantityChange = {
              draft = draft.copy(quantity = it)
              calls += "quantity:$it"
            },
            onPriceChange = {
              draft = draft.copy(price = it)
              calls += "price:$it"
            },
            onSave = { calls += "save" },
            onCancel = { calls += "cancel" },
        )
      }
    }
  }

  private fun string(id: Int, vararg args: Any) = composeTestRule.activity.getString(id, *args)

  @Test
  fun showsTheDraftValues() {
    setDialog()

    composeTestRule.onNodeWithTag(StagedItemEditTestTags.DIALOG).assertIsDisplayed()
    composeTestRule.onNodeWithTag(StagedItemEditTestTags.NAME).assertTextContains("Milk")
    composeTestRule.onNodeWithTag(StagedItemEditTestTags.QUANTITY).assertTextContains("2")
    composeTestRule.onNodeWithTag(StagedItemEditTestTags.PRICE).assertTextContains("1.89")
    composeTestRule.onNodeWithTag(StagedItemEditTestTags.ERROR).assertDoesNotExist()
  }

  @Test
  fun reportsTypingSaveAndCancel() {
    setDialog()

    composeTestRule.onNodeWithTag(StagedItemEditTestTags.NAME).performTextReplacement("Oat milk")
    composeTestRule.onNodeWithTag(StagedItemEditTestTags.QUANTITY).performTextReplacement("3")
    composeTestRule.onNodeWithTag(StagedItemEditTestTags.PRICE).performTextReplacement("2.40")
    composeTestRule.onNodeWithTag(StagedItemEditTestTags.SAVE).performClick()
    composeTestRule.onNodeWithTag(StagedItemEditTestTags.CANCEL).performClick()

    assertEquals(listOf("name:Oat milk", "quantity:3", "price:2.40", "save", "cancel"), calls)
  }

  @Test
  fun showsTheMessageForEachError() {
    setDialog()
    val expected =
        mapOf(
            StagedItemError.NAME_REQUIRED to string(R.string.fridge_name_required),
            StagedItemError.INVALID_QUANTITY to
                string(R.string.fridge_quantity_invalid, ItemQuantity.MIN, ItemQuantity.MAX),
            StagedItemError.INVALID_PRICE to string(R.string.staging_price_invalid),
        )

    for ((error, message) in expected) {
      draft = draft.copy(error = error)
      composeTestRule
          .onNodeWithTag(StagedItemEditTestTags.ERROR)
          .assertIsDisplayed()
          .assertTextEquals(message)
    }
  }
}
