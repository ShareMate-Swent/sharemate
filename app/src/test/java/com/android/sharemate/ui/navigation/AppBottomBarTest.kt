// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.android.sharemate.R
import com.android.sharemate.ui.theme.ShareMateTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Checks the tabs of the bottom bar, its inactive add button and the profile photo entry. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp")
class AppBottomBarTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val selected = mutableListOf<TopLevelDestination>()

  private fun showBar(route: String?) =
      compose.setContent {
        ShareMateTheme { AppBottomBar(currentRoute = route, onSelect = { selected += it }) }
      }

  @Test
  fun barOffersTheFourTabsInOrderAroundTheAddButton() {
    showBar(TopLevelDestination.FRIDGE.route)
    val order =
        listOf(
                NavigationTestTags.FRIDGE_TAB,
                NavigationTestTags.RECIPES_TAB,
                NavigationTestTags.ADD_BUTTON,
                NavigationTestTags.RECEIPTS_TAB,
                NavigationTestTags.CHAT_TAB)
            .map { compose.onNodeWithTag(it).assertIsDisplayed().getUnclippedBoundsInRoot().left }
    assertEquals(order.sortedBy { it.value }, order)
    compose.onNodeWithTag(NavigationTestTags.SETTINGS_TAB).assertDoesNotExist()
  }

  @Test
  fun tappingATabReportsItAndTheCurrentOneIsSelected() {
    showBar(TopLevelDestination.CHAT.route)
    compose.onNodeWithTag(NavigationTestTags.CHAT_TAB).assertIsSelected()
    compose.onNodeWithTag(NavigationTestTags.RECEIPTS_TAB).assertIsNotSelected().performClick()
    assertEquals(listOf(TopLevelDestination.RECEIPTS), selected)
  }

  @Test
  fun addButtonIsAnnouncedAndDoesNothingYet() {
    showBar(TopLevelDestination.FRIDGE.route)
    compose
        .onNodeWithTag(NavigationTestTags.ADD_BUTTON)
        .assertContentDescriptionEquals(compose.activity.getString(R.string.nav_add_item))
        .assertHasClickAction()
        .performClick()
    assertEquals(emptyList<TopLevelDestination>(), selected)
    compose.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).assertIsSelected()
  }

  @Test
  fun profileEntryShowsThePlaceholderWithoutAPhotoAndOpensSettings() {
    var opened = 0
    compose.setContent { ShareMateTheme { ProfileEntry(photoUrl = null, onClick = { opened++ }) } }
    compose.onNodeWithTag(ProfileEntryTestTags.PLACEHOLDER, useUnmergedTree = true).assertExists()
    compose.onNodeWithTag(ProfileEntryTestTags.PHOTO, useUnmergedTree = true).assertDoesNotExist()
    compose
        .onNodeWithTag(NavigationTestTags.SETTINGS_TAB)
        .assertContentDescriptionEquals(compose.activity.getString(R.string.nav_open_settings))
        .assertHeightIsAtLeast(ProfileEntryMinimumTouch)
        .performClick()
    assertEquals(1, opened)
  }

  @Test
  fun profileEntryShowsThePhotoWhenThereIsOne() {
    compose.setContent {
      ShareMateTheme { ProfileEntry(photoUrl = "https://example.org/me.jpg", onClick = {}) }
    }
    compose.onNodeWithTag(ProfileEntryTestTags.PHOTO, useUnmergedTree = true).assertExists()
  }

  private companion object {
    val ProfileEntryMinimumTouch = androidx.compose.ui.unit.Dp(48f)
  }
}
