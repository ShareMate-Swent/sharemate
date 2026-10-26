// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.settings

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.android.sharemate.R
import com.android.sharemate.model.auth.SignInProvider
import com.android.sharemate.model.household.FridgeType
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
  private var switches = 0
  private var leaveState by mutableStateOf(LeaveHouseholdUiState())
  private val googleState =
      SettingsUiState(
          email = "alex.morgan@example.com",
          displayName = "Alex Morgan",
          provider = SignInProvider.GOOGLE,
          householdName = "Rue de Lausanne flat",
          memberCount = 3,
          inviteCode = "K7XM4P")

  private fun showSettings(
      state: LeaveHouseholdUiState = LeaveHouseholdUiState(),
      settings: SettingsUiState = googleState,
  ) {
    leaveState = state
    composeTestRule.setContent {
      MaterialTheme {
        SettingsScreen(
            state = settings,
            leaveState = leaveState,
            onLeaveHousehold = { leaveRequests++ },
            onCancelLeave = { cancels++ },
            onConfirmLeave = { confirms++ },
            onLeft = { leftCalls++ },
            onSwitchGoogleAccount = { switches++ },
            onSignOut = { signOuts++ })
      }
    }
  }

  @Test
  fun leaveAndSignOutButtonsInvokeTheirCallbacksAndNoDialogIsShown() {
    showSettings()

    composeTestRule.onNodeWithTag(SettingsTestTags.LEAVE_HOUSEHOLD).performScrollTo().performClick()
    composeTestRule.onNodeWithTag(SettingsTestTags.SIGN_OUT).performScrollTo().performClick()

    assertEquals(1, leaveRequests)
    assertEquals(1, signOuts)
    composeTestRule.onNodeWithTag(SettingsTestTags.LEAVE_DIALOG).assertDoesNotExist()
  }

  @Test
  fun googleAccountShowsProfileConnectedStatusAndSwitchAction() {
    showSettings()

    composeTestRule.onNodeWithTag(SettingsTestTags.PROFILE_NAME).assertTextEquals("Alex Morgan")
    composeTestRule
        .onNodeWithTag(SettingsTestTags.PROFILE_EMAIL)
        .assertTextEquals("alex.morgan@example.com")
    composeTestRule.onNodeWithTag(SettingsTestTags.PROFILE_PLACEHOLDER).assertIsDisplayed()
    composeTestRule.onNodeWithTag(SettingsTestTags.PROFILE_PHOTO).assertDoesNotExist()
    composeTestRule.onNodeWithTag(SettingsTestTags.ACCOUNT_TITLE).assertTextEquals("Google account")
    composeTestRule.onNodeWithTag(SettingsTestTags.ACCOUNT_STATUS).assertTextEquals("Connected")
    composeTestRule.onNodeWithTag(SettingsTestTags.ACCOUNT_ERROR).assertDoesNotExist()
    composeTestRule
        .onNodeWithTag(SettingsTestTags.SWITCH_GOOGLE_ACCOUNT)
        .performScrollTo()
        .assertIsEnabled()
        .performClick()

    assertEquals(1, switches)
    assertEquals(0, signOuts)
  }

  @Test
  fun emailAccountWithoutNameShowsTheEmailOnceAndNoSwitchAction() {
    showSettings(
        settings =
            googleState.copy(
                displayName = null, provider = SignInProvider.EMAIL, email = "sam@example.com"))

    composeTestRule.onNodeWithTag(SettingsTestTags.PROFILE_NAME).assertTextEquals("sam@example.com")
    composeTestRule.onNodeWithTag(SettingsTestTags.PROFILE_EMAIL).assertDoesNotExist()
    composeTestRule.onNodeWithTag(SettingsTestTags.ACCOUNT_TITLE).assertTextEquals("Email account")
    composeTestRule
        .onNodeWithTag(SettingsTestTags.ACCOUNT_STATUS)
        .assertTextEquals("Signed in with email")
    composeTestRule.onNodeWithTag(SettingsTestTags.SWITCH_GOOGLE_ACCOUNT).assertDoesNotExist()
    composeTestRule.onNodeWithTag(SettingsTestTags.SIGN_OUT).performScrollTo().performClick()

    assertEquals(1, signOuts)
  }

  @Test
  fun aPhotoUrlIsDrawnOverThePlaceholderUntilItLoads() {
    showSettings(settings = googleState.copy(photoUrl = "content://sharemate.test/missing.png"))

    composeTestRule.onNodeWithTag(SettingsTestTags.PROFILE_PHOTO).assertExists()
    composeTestRule.onNodeWithTag(SettingsTestTags.PROFILE_PLACEHOLDER).assertIsDisplayed()
  }

  @Test
  fun whileSwitchingAProgressIndicatorShowsAndBothActionsAreDisabled() {
    showSettings(settings = googleState.copy(isSwitchingAccount = true))

    composeTestRule.onNodeWithTag(SettingsTestTags.SWITCH_LOADING).assertExists()
    composeTestRule.onNodeWithTag(SettingsTestTags.SWITCH_GOOGLE_ACCOUNT).assertIsNotEnabled()
    composeTestRule.onNodeWithTag(SettingsTestTags.SIGN_OUT).assertIsNotEnabled()
  }

  @Test
  fun eachSwitchFailureShowsItsMessage() {
    val messages =
        mapOf(
            SettingsAccountError.GOOGLE_UNAVAILABLE to R.string.settings_switch_unavailable,
            SettingsAccountError.NETWORK to R.string.settings_switch_network,
            SettingsAccountError.SWITCH_FAILED to R.string.settings_switch_failed)
    var error by mutableStateOf(SettingsAccountError.GOOGLE_UNAVAILABLE)
    composeTestRule.setContent {
      MaterialTheme {
        SettingsScreen(
            state = googleState.copy(accountError = error),
            leaveState = LeaveHouseholdUiState(),
            onLeaveHousehold = {},
            onCancelLeave = {},
            onConfirmLeave = {},
            onLeft = {},
            onSwitchGoogleAccount = {},
            onSignOut = {})
      }
    }

    for ((accountError, message) in messages) {
      composeTestRule.runOnIdle { error = accountError }
      composeTestRule
          .onNodeWithTag(SettingsTestTags.ACCOUNT_ERROR)
          .assertTextEquals(composeTestRule.activity.getString(message))
    }
  }

  @Test
  fun householdCardShowsNameMemberCountAndInviteCode() {
    showSettings()

    composeTestRule
        .onNodeWithTag(SettingsTestTags.HOUSEHOLD_NAME)
        .performScrollTo()
        .assertTextEquals("Rue de Lausanne flat")
    composeTestRule.onNodeWithTag(SettingsTestTags.HOUSEHOLD_MEMBERS).assertTextEquals("3 members")
    composeTestRule.onNodeWithTag(SettingsTestTags.INVITE_CODE).assertTextEquals("K7XM4P")
  }

  @Test
  fun privateHouseholdHidesTheInviteCodeButKeepsLeaveHousehold() {
    showSettings(settings = googleState.copy(fridgeType = FridgeType.PRIVATE, memberCount = 1))

    composeTestRule
        .onNodeWithTag(SettingsTestTags.HOUSEHOLD_NAME)
        .performScrollTo()
        .assertTextEquals("Rue de Lausanne flat")
    composeTestRule.onNodeWithTag(SettingsTestTags.INVITE_CODE).assertDoesNotExist()
    composeTestRule.onNodeWithTag(SettingsTestTags.COPY_INVITE_CODE).assertDoesNotExist()
    composeTestRule.onNodeWithTag(SettingsTestTags.LEAVE_HOUSEHOLD).performScrollTo().performClick()

    assertEquals(1, leaveRequests)
  }

  @Test
  fun aSingleMemberIsNotPluralized() {
    showSettings(settings = googleState.copy(memberCount = 1))

    composeTestRule.onNodeWithTag(SettingsTestTags.HOUSEHOLD_MEMBERS).assertTextEquals("1 member")
  }

  @Test
  fun copyButtonPutsTheInviteCodeInTheClipboard() {
    showSettings()

    composeTestRule
        .onNodeWithTag(SettingsTestTags.COPY_INVITE_CODE)
        .performScrollTo()
        .performClick()

    val clipboard =
        composeTestRule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    assertEquals("K7XM4P", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
  }

  @Test
  fun leaveHouseholdIsInsideTheHouseholdCard() {
    showSettings()

    composeTestRule
        .onNodeWithTag(SettingsTestTags.LEAVE_HOUSEHOLD)
        .assert(hasAnyAncestor(hasTestTag(SettingsTestTags.HOUSEHOLD_CARD)))
        .performScrollTo()
        .performClick()

    assertEquals(1, leaveRequests)
  }

  @Test
  fun confirmationNamesTheHouseholdAndOffersCancelAndLeave() {
    showSettings(LeaveHouseholdUiState(isConfirming = true))

    composeTestRule.onNodeWithTag(SettingsTestTags.LEAVE_DIALOG).assertIsDisplayed()
    // The name also appears in the Household card; check the one in the dialog.
    composeTestRule
        .onNode(
            hasText("Rue de Lausanne flat", substring = true) and
                hasAnyAncestor(hasTestTag(SettingsTestTags.LEAVE_DIALOG)))
        .assertIsDisplayed()
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

    composeTestRule
        .onNodeWithTag(SettingsTestTags.LEAVE_ERROR)
        .assertIsDisplayed()
        .assertTextEquals(composeTestRule.activity.getString(R.string.leave_household_failed))
    composeTestRule.onNodeWithTag(SettingsTestTags.LEAVE_CONFIRM).assertTextEquals("Try again")
    composeTestRule.onNodeWithTag(SettingsTestTags.LEAVE_CONFIRM).performClick()

    assertEquals(1, confirms)
  }

  @Test
  fun endedSessionShowsItsOwnMessageInsteadOfTheConnectionOne() {
    showSettings(LeaveHouseholdUiState(isConfirming = true, error = LeaveHouseholdError.NO_SESSION))

    composeTestRule
        .onNodeWithTag(SettingsTestTags.LEAVE_ERROR)
        .assertIsDisplayed()
        .assertTextEquals(composeTestRule.activity.getString(R.string.leave_household_no_session))
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
