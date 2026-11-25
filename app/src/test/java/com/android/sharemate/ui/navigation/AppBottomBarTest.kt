// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.android.sharemate.R
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.ui.theme.ShareMateTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Checks the tabs of the bottom bar, its add button and the profile photo entry. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp")
class AppBottomBarTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val selected = mutableListOf<TopLevelDestination>()
  private var added = 0

  private fun showBar(route: String?) =
      compose.setContent {
        ShareMateTheme {
          AppBottomBar(
              currentRoute = route,
              tabs = TopLevelDestination.bottomBar,
              onSelect = { selected += it },
              onAdd = { added++ })
        }
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
    compose.onNodeWithTag(NavigationTestTags.PROFILE_ENTRY).assertDoesNotExist()
  }

  @Test
  fun privateFridgeBarHasNoChatTab() {
    compose.setContent {
      ShareMateTheme {
        AppBottomBar(
            currentRoute = TopLevelDestination.FRIDGE.route,
            tabs = TopLevelDestination.bottomBarFor(FridgeType.PRIVATE),
            onSelect = { selected += it },
            onAdd = { added++ })
      }
    }
    val order =
        listOf(
                NavigationTestTags.FRIDGE_TAB,
                NavigationTestTags.RECIPES_TAB,
                NavigationTestTags.ADD_BUTTON,
                NavigationTestTags.RECEIPTS_TAB)
            .map { compose.onNodeWithTag(it).assertIsDisplayed().getUnclippedBoundsInRoot().left }
    assertEquals(order.sortedBy { it.value }, order)
    compose.onNodeWithTag(NavigationTestTags.CHAT_TAB).assertDoesNotExist()
  }

  @Test
  fun tappingATabReportsItAndTheCurrentOneIsSelected() {
    showBar(TopLevelDestination.CHAT.route)
    compose.onNodeWithTag(NavigationTestTags.CHAT_TAB).assertIsSelected()
    compose.onNodeWithTag(NavigationTestTags.RECEIPTS_TAB).assertIsNotSelected().performClick()
    assertEquals(listOf(TopLevelDestination.RECEIPTS), selected)
  }

  @Test
  fun addButtonIsAnnouncedAndReportsATapWithoutChangingTab() {
    showBar(TopLevelDestination.FRIDGE.route)
    compose
        .onNodeWithTag(NavigationTestTags.ADD_BUTTON)
        .assertContentDescriptionEquals(compose.activity.getString(R.string.nav_add_item))
        .assertHasClickAction()
        .performClick()
    assertEquals(1, added)
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
        .onNodeWithTag(NavigationTestTags.PROFILE_ENTRY)
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

  @Test
  fun profileEntryIsAnOvalWithTheSettingsIconBeforeThePhoto() {
    var opened = 0
    compose.setContent {
      ShareMateTheme {
        ProfileEntry(photoUrl = "https://example.org/me.jpg", onClick = { opened++ })
      }
    }
    val icon =
        compose
            .onNodeWithTag(ProfileEntryTestTags.SETTINGS_ICON, useUnmergedTree = true)
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
    val photo =
        compose
            .onNodeWithTag(ProfileEntryTestTags.PHOTO, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
    assertTrue("The settings icon must come before the photo", icon.right <= photo.left)
    val oval = compose.onNodeWithTag(NavigationTestTags.PROFILE_ENTRY)
    val bounds = oval.getUnclippedBoundsInRoot()
    assertTrue(
        "The entry must be wider than tall",
        bounds.right - bounds.left > bounds.bottom - bounds.top)
    // Tapping the settings icon side of the oval, not only the photo, opens Settings.
    oval.performTouchInput { click(centerLeft + Offset(4f, 0f)) }
    assertEquals(1, opened)
  }

  private companion object {
    val ProfileEntryMinimumTouch = androidx.compose.ui.unit.Dp(48f)
  }
}
