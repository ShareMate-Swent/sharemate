// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.android.sharemate.ui.navigation.NavigationTestTags
import com.android.sharemate.ui.settings.EditProfileTestTags
import com.android.sharemate.ui.settings.SettingsTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Opens the Edit profile page from Settings in the real activity, with Firebase mocked. */
@RunWith(RobolectricTestRunner::class)
class EditProfileNavigationTest {
  @get:Rule(order = 0) val firebase = FirebaseAuthTestRule(startSignedIn = true)
  @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

  private fun openEditProfile() {
    compose.onNodeWithTag(NavigationTestTags.PROFILE_ENTRY).performClick()
    compose.onNodeWithTag(SettingsTestTags.EDIT_PROFILE).performScrollTo().performClick()
    compose.onNodeWithTag(EditProfileTestTags.PSEUDO_FIELD).assertIsDisplayed()
  }

  @Test
  fun theLinkInSettingsOpensThePageWithoutTheBarAndBackReturnsToSettings() {
    openEditProfile()

    compose.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).assertDoesNotExist()

    compose.onNodeWithTag(EditProfileTestTags.BACK).performClick()

    compose.onNodeWithTag(EditProfileTestTags.PSEUDO_FIELD).assertDoesNotExist()
    compose.onNodeWithTag(SettingsTestTags.EDIT_PROFILE).assertExists()
  }

  @Test
  fun backFromThePageThenSettingsReturnsToTheFridgeWithTheBar() {
    openEditProfile()

    compose.onNodeWithTag(EditProfileTestTags.BACK).performClick()
    compose.onNodeWithTag(SettingsTestTags.BACK).performClick()

    compose.onNodeWithTag(SettingsTestTags.EDIT_PROFILE).assertDoesNotExist()
    compose.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).assertIsDisplayed().assertIsSelected()
  }

  @Test
  fun theLinkCanBeTappedAgainAfterComingBack() {
    openEditProfile()
    compose.onNodeWithTag(EditProfileTestTags.BACK).performClick()

    compose.onNodeWithTag(SettingsTestTags.EDIT_PROFILE).performScrollTo().performClick()

    compose.onNodeWithTag(EditProfileTestTags.PSEUDO_FIELD).assertIsDisplayed()
  }
}
