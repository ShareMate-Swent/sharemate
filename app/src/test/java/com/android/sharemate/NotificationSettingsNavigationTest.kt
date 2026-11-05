// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.android.sharemate.resources.C
import com.android.sharemate.ui.navigation.NavigationTestTags
import com.android.sharemate.ui.settings.NotificationSettingsTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationSettingsNavigationTest {
  @get:Rule(order = 0)
  val firebase = FirebaseAuthTestRule(startSignedIn = true, pendingHouseholdLookup = true)
  @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

  private fun openNotificationSettings() {
    compose.runOnIdle { firebase.resolveInitialHousehold() }
    compose.onNodeWithTag(C.Tag.fridge_add_item).assertIsDisplayed()
    compose.onNodeWithTag(NavigationTestTags.SETTINGS_TAB).performClick()
    compose.onNodeWithTag(NotificationSettingsTestTags.OPEN_ENTRY).performScrollTo().performClick()
  }

  @Test
  fun entryOpensTheSubPageWhileTheBottomBarStaysVisible() {
    openNotificationSettings()

    compose.onNodeWithTag(NotificationSettingsTestTags.TITLE).assertIsDisplayed()
    compose.onNodeWithTag(NotificationSettingsTestTags.SYSTEM_CARD).assertIsDisplayed()
    compose.onNodeWithTag(NavigationTestTags.SETTINGS_TAB).assertIsDisplayed()
    compose.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).assertIsDisplayed()
  }

  @Test
  fun backArrowReturnsToTheSettingsRoot() {
    openNotificationSettings()

    compose.onNodeWithTag(NotificationSettingsTestTags.BACK).performClick()

    compose.onNodeWithTag(NotificationSettingsTestTags.TITLE).assertDoesNotExist()
    compose.onNodeWithTag(NotificationSettingsTestTags.OPEN_ENTRY).assertExists()
  }

  @Test
  fun leavingForAnotherTabAndComingBackRestoresTheSubPage() {
    openNotificationSettings()

    compose.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).performClick()
    compose.onNodeWithTag(C.Tag.fridge_add_item).assertIsDisplayed()
    compose.onNodeWithTag(NavigationTestTags.SETTINGS_TAB).performClick()

    compose.onNodeWithTag(NotificationSettingsTestTags.TITLE).assertIsDisplayed()
  }

  @Test
  fun tappingTheActiveSettingsTabReturnsToTheSettingsRoot() {
    openNotificationSettings()

    compose.onNodeWithTag(NavigationTestTags.SETTINGS_TAB).performClick()

    compose.onNodeWithTag(NotificationSettingsTestTags.TITLE).assertDoesNotExist()
    compose.onNodeWithTag(NotificationSettingsTestTags.OPEN_ENTRY).assertExists()
  }
}
