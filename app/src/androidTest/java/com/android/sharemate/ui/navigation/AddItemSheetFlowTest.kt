// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.ui.theme.ShareMateTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Taps the add button and the options of its sheet on a device, as the app connects them. */
@RunWith(AndroidJUnit4::class)
class AddItemSheetFlowTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val menu = AddItemMenuViewModel()

  private fun showBarAndSheet() =
      compose.setContent {
        ShareMateTheme {
          val open by menu.isSheetOpen.collectAsState()
          AppBottomBar(
              currentRoute = TopLevelDestination.CHAT.route, onSelect = {}, onAdd = menu::openSheet)
          if (open) AddItemSheet(onChoose = menu::choose, onDismiss = menu::dismissSheet)
        }
      }

  @Test
  fun addButtonOpensTheSheetAndAnOptionClosesItWithTheChoice() {
    showBarAndSheet()
    compose.onNodeWithTag(NavigationTestTags.ADD_BUTTON).performClick()
    compose
        .onNodeWithTag(AddItemSheetTestTags.option(AddItemMethod.BARCODE))
        .assertIsDisplayed()
        .performClick()
    compose.onNodeWithTag(AddItemSheetTestTags.SHEET).assertDoesNotExist()
    compose.runOnIdle {
      assertFalse(menu.isSheetOpen.value)
      assertEquals(AddItemMethod.BARCODE, menu.request.value)
    }
  }

  @Test
  fun cancelClosesTheSheetWithoutAChoice() {
    showBarAndSheet()
    compose.onNodeWithTag(NavigationTestTags.ADD_BUTTON).performClick()
    compose.onNodeWithTag(AddItemSheetTestTags.CANCEL).assertIsDisplayed().performClick()
    compose.onNodeWithTag(AddItemSheetTestTags.SHEET).assertDoesNotExist()
    compose.runOnIdle { assertNull(menu.request.value) }
  }
}
