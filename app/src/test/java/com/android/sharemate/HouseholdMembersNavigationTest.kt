// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.android.sharemate.ui.household.HouseholdMembersTestTags
import com.android.sharemate.ui.navigation.NavigationTestTags
import com.android.sharemate.ui.settings.SettingsTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Opens the members list from Settings in the real activity, with Firebase replaced by mocks. */
@RunWith(RobolectricTestRunner::class)
class HouseholdMembersNavigationTest {
  @get:Rule(order = 0) val firebase = FirebaseAuthTestRule(startSignedIn = true)
  @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

  private fun openMembers() {
    compose.onNodeWithTag(NavigationTestTags.SETTINGS_TAB).performClick()
    compose.onNodeWithTag(SettingsTestTags.SHOW_MEMBERS).performScrollTo().performClick()
    compose.onNodeWithTag(HouseholdMembersTestTags.SCREEN).assertIsDisplayed()
  }

  @Test
  fun theMemberCountOpensTheListUnderTheSettingsTabAndBackReturnsToSettings() {
    openMembers()

    compose.onNodeWithTag(NavigationTestTags.SETTINGS_TAB).assertIsSelected()

    compose.onNodeWithTag(HouseholdMembersTestTags.BACK).performClick()

    compose.onNodeWithTag(HouseholdMembersTestTags.SCREEN).assertDoesNotExist()
    compose.onNodeWithTag(SettingsTestTags.SHOW_MEMBERS).assertExists()
  }

  @Test
  fun tappingTheSettingsTabOnTheListReturnsToSettings() {
    openMembers()

    compose.onNodeWithTag(NavigationTestTags.SETTINGS_TAB).performClick()

    compose.onNodeWithTag(HouseholdMembersTestTags.SCREEN).assertDoesNotExist()
    compose.onNodeWithTag(SettingsTestTags.SHOW_MEMBERS).assertExists()
  }

  @Test
  fun anotherTabLeavesTheListAndSettingsReopensIt() {
    openMembers()

    compose.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).performClick()
    compose.onNodeWithTag(HouseholdMembersTestTags.SCREEN).assertDoesNotExist()

    // Like the other tabs, Settings comes back where the user left it.
    compose.onNodeWithTag(NavigationTestTags.SETTINGS_TAB).performClick()
    compose.onNodeWithTag(HouseholdMembersTestTags.SCREEN).assertIsDisplayed()
  }
}
