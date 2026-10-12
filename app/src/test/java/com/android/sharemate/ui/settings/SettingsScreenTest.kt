// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.android.sharemate.ui.household.LeaveHouseholdError
import com.android.sharemate.ui.household.LeaveHouseholdUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsScreenTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private var leaveRequests = 0
  private var cancels = 0
  private var confirms = 0
  private var leftCalls = 0
  private var signOuts = 0
  private var leaveState by mutableStateOf(LeaveHouseholdUiState())

  private fun showSettings(state: LeaveHouseholdUiState = LeaveHouseholdUiState()) {
    leaveState = state
    composeTestRule.setContent {
      MaterialTheme {
        SettingsScreen(
            householdName = "Rue de Lausanne flat",
            leaveState = leaveState,
            onLeaveHousehold = { leaveRequests++ },
            onCancelLeave = { cancels++ },
            onConfirmLeave = { confirms++ },
            onLeft = { leftCalls++ },
            onSignOut = { signOuts++ })
      }
    }
  }

  @Test
  fun leaveAndSignOutButtonsInvokeTheirCallbacksAndNoDialogIsShown() {
    showSettings()

    composeTestRule.onNodeWithTag(SettingsTestTags.LEAVE_HOUSEHOLD).performClick()
    composeTestRule.onNodeWithTag(SettingsTestTags.SIGN_OUT).performClick()

    assertEquals(1, leaveRequests)
    assertEquals(1, signOuts)
    composeTestRule.onNodeWithTag(SettingsTestTags.LEAVE_DIALOG).assertDoesNotExist()
  }

  @Test
  fun confirmationNamesTheHouseholdAndOffersCancelAndLeave() {
    showSettings(LeaveHouseholdUiState(isConfirming = true))

    composeTestRule.onNodeWithTag(SettingsTestTags.LEAVE_DIALOG).assertIsDisplayed()
    composeTestRule.onNodeWithText("Rue de Lausanne flat", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag(SettingsTestTags.LEAVE_ERROR).assertDoesNotExist()
    composeTestRule.onNodeWithTag(SettingsTestTags.LEAVE_CONFIRM).assertTextEquals("Leave")
    composeTestRule.onNodeWithTag(SettingsTestTags.LEAVE_CONFIRM).performClick()
    composeTestRule.onNodeWithTag(SettingsTestTags.LEAVE_CANCEL).performClick()

    assertEquals(1, confirms)
    assertEquals(1, cancels)
  }

  @Test
  fun whileLeavingAProgressIndicatorReplacesLeaveAndCancelIsDisabled() {
    showSettings(LeaveHouseholdUiState(isConfirming = true, isLeaving = true))

    composeTestRule.onNodeWithTag(SettingsTestTags.LEAVE_LOADING).assertIsDisplayed()
    composeTestRule.onNodeWithTag(SettingsTestTags.LEAVE_CONFIRM).assertDoesNotExist()
    composeTestRule.onNodeWithTag(SettingsTestTags.LEAVE_CANCEL).assertIsNotEnabled()
  }

  @Test
  fun failureShowsTheErrorAndOffersToTryAgain() {
    showSettings(
        LeaveHouseholdUiState(isConfirming = true, error = LeaveHouseholdError.LEAVE_FAILED))

    composeTestRule.onNodeWithTag(SettingsTestTags.LEAVE_ERROR).assertIsDisplayed()
    composeTestRule.onNodeWithTag(SettingsTestTags.LEAVE_CONFIRM).assertTextEquals("Try again")
    composeTestRule.onNodeWithTag(SettingsTestTags.LEAVE_CONFIRM).performClick()

    assertEquals(1, confirms)
  }

  @Test
  fun leftIsReportedOnceAcrossRecomposition() {
    showSettings(LeaveHouseholdUiState(isConfirming = true, isLeaving = true))
    assertEquals(0, leftCalls)

    composeTestRule.runOnIdle { leaveState = LeaveHouseholdUiState(left = true) }
    composeTestRule.waitForIdle()
    composeTestRule.runOnIdle { leaveState = leaveState.copy(error = null, isLeaving = false) }
    composeTestRule.waitForIdle()

    assertEquals(1, leftCalls)
    composeTestRule.onNodeWithTag(SettingsTestTags.LEAVE_DIALOG).assertDoesNotExist()
  }
}
