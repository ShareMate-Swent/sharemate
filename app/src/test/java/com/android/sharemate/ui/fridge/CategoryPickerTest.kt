// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.fridge

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.android.sharemate.model.item.ItemCategory
import com.android.sharemate.resources.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Checks the category field of the item form. */
@RunWith(RobolectricTestRunner::class)
class CategoryPickerTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun pickerShowsNoCategoryByDefaultAndReportsThePickedCategory() {
    val selected = mutableStateOf<ItemCategory?>(null)
    compose.setContent {
      MaterialTheme {
        CategoryPicker(
            selected = selected.value, onSelect = { selected.value = it }, enabled = true)
      }
    }
    compose.onNodeWithTag(C.Tag.fridge_category_input).assertTextContains("No category")

    compose.onNodeWithTag(C.Tag.fridge_category_input).performClick()
    compose.onNodeWithTag(C.Tag.fridgeCategoryOption(ItemCategory.MEAT_FISH)).performClick()
    assertEquals(ItemCategory.MEAT_FISH, selected.value)
    compose.onNodeWithTag(C.Tag.fridge_category_input).assertTextContains("Meat & fish")

    compose.onNodeWithTag(C.Tag.fridge_category_input).performClick()
    compose.onNodeWithTag(C.Tag.fridgeCategoryOption(null)).performClick()
    assertNull(selected.value)
  }
}
