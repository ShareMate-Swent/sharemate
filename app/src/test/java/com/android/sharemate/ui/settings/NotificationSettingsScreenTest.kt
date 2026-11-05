// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.android.sharemate.R
import com.android.sharemate.model.user.ExpirationPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationSettingsScreenTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private var state by mutableStateOf(NotificationSettingsUiState(isLoaded = true))
  private var systemAllowed by mutableStateOf(true)
  private var backClicks = 0
  private var systemSettingsClicks = 0
  private val soonChanges = mutableListOf<Int>()
  private val soonRepeatChanges = mutableListOf<Int?>()
  private val expiredRepeatChanges = mutableListOf<Int?>()

  private fun show(initial: NotificationSettingsUiState = state) {
    state = initial
    composeTestRule.setContent {
      MaterialTheme {
        NotificationSettingsScreen(
            state = state,
            isSystemAllowed = systemAllowed,
            onBack = { backClicks++ },
            onOpenSystemSettings = { systemSettingsClicks++ },
            onSoonDaysChange = { soonChanges += it },
            onSoonRepeatChange = { soonRepeatChanges += it },
            onExpiredRepeatChange = { expiredRepeatChanges += it })
      }
    }
  }

  private fun preferences(
      soonDays: Int = 3,
      soonRepeatDays: Int? = 1,
      expiredRepeatDays: Int? = 1,
  ) =
      NotificationSettingsUiState(
          ExpirationPreferences(soonDays, soonRepeatDays, expiredRepeatDays), true)

  private fun string(id: Int, vararg args: Any) = composeTestRule.activity.getString(id, *args)

  private fun days(count: Int) =
      composeTestRule.activity.resources.getQuantityString(
          R.plurals.notification_settings_days, count, count)

  private fun tag(tag: String) = composeTestRule.onNodeWithTag(tag).performScrollTo()

  @Test
  fun backArrowLeavesTheSubPage() {
    show()

    composeTestRule.onNodeWithTag(NotificationSettingsTestTags.TITLE).assertIsDisplayed()
    composeTestRule.onNodeWithTag(NotificationSettingsTestTags.BACK).performClick()

    assertEquals(1, backClicks)
  }

  @Test
  fun preferencesAreHiddenUntilTheSavedValuesAreLoaded() {
    show(NotificationSettingsUiState(isLoaded = false))

    composeTestRule.onNodeWithTag(NotificationSettingsTestTags.SYSTEM_CARD).assertIsDisplayed()
    composeTestRule.onNodeWithTag(NotificationSettingsTestTags.SOON_CARD).assertDoesNotExist()
    composeTestRule.onNodeWithTag(NotificationSettingsTestTags.FREQUENCY_CARD).assertDoesNotExist()
  }

  @Test
  fun systemCardWarnsWhenPhoneNotificationsAreOffAndOpensPhoneSettings() {
    systemAllowed = false
    show()

    composeTestRule
        .onNodeWithTag(NotificationSettingsTestTags.SYSTEM_STATUS)
        .assertTextEquals(string(R.string.notification_settings_system_blocked))
    composeTestRule.onNodeWithTag(NotificationSettingsTestTags.OPEN_SYSTEM_SETTINGS).performClick()

    assertEquals(1, systemSettingsClicks)
  }

  @Test
  fun systemCardConfirmsWhenPhoneNotificationsAreOn() {
    show()

    composeTestRule
        .onNodeWithTag(NotificationSettingsTestTags.SYSTEM_STATUS)
        .assertTextEquals(string(R.string.notification_settings_system_allowed))
  }

  @Test
  fun soonWindowShowsTheSavedValueAndReportsEachStep() {
    show(preferences(soonDays = 10))

    tag(NotificationSettingsTestTags.SOON_VALUE).assertTextEquals(days(10))
    tag(NotificationSettingsTestTags.SOON_INCREASE).performClick()
    tag(NotificationSettingsTestTags.SOON_DECREASE).performClick()

    assertEquals(listOf(11, 9), soonChanges)
  }

  @Test
  fun soonWindowCannotGoBelowOneDayAndUsesTheSingular() {
    show(preferences(soonDays = ExpirationPreferences.SOON_DAYS_RANGE.first))

    tag(NotificationSettingsTestTags.SOON_VALUE).assertTextEquals(days(1))
    tag(NotificationSettingsTestTags.SOON_DECREASE).assertIsNotEnabled()
    tag(NotificationSettingsTestTags.SOON_INCREASE).assertIsEnabled()
  }

  @Test
  fun soonWindowCannotGoAboveSixtyDays() {
    show(preferences(soonDays = ExpirationPreferences.SOON_DAYS_RANGE.last))

    tag(NotificationSettingsTestTags.SOON_VALUE).assertTextEquals(days(60))
    tag(NotificationSettingsTestTags.SOON_INCREASE).assertIsNotEnabled()
    tag(NotificationSettingsTestTags.SOON_DECREASE).assertIsEnabled()
  }

  @Test
  fun repeatSettingsShowTheirOwnValuesIndependently() {
    show(preferences(soonRepeatDays = 4, expiredRepeatDays = 9))

    tag(NotificationSettingsTestTags.SOON_REPEAT_VALUE).assertTextEquals(days(4))
    tag(NotificationSettingsTestTags.EXPIRED_REPEAT_VALUE).assertTextEquals(days(9))

    tag(NotificationSettingsTestTags.SOON_REPEAT_INCREASE).performClick()
    tag(NotificationSettingsTestTags.EXPIRED_REPEAT_DECREASE).performClick()

    assertEquals(listOf<Int?>(5), soonRepeatChanges)
    assertEquals(listOf<Int?>(8), expiredRepeatChanges)
  }

  @Test
  fun neverHidesTheStepperAndSwitchingBackOnStartsFromTheDefault() {
    show(preferences(soonRepeatDays = null, expiredRepeatDays = null))

    tag(NotificationSettingsTestTags.SOON_REPEAT_SWITCH).assertIsOff()
    tag(NotificationSettingsTestTags.EXPIRED_REPEAT_SWITCH).assertIsOff()
    composeTestRule
        .onNodeWithTag(NotificationSettingsTestTags.SOON_REPEAT_VALUE)
        .assertDoesNotExist()
    composeTestRule
        .onNodeWithTag(NotificationSettingsTestTags.EXPIRED_REPEAT_VALUE)
        .assertDoesNotExist()

    tag(NotificationSettingsTestTags.SOON_REPEAT_SWITCH).performClick()
    tag(NotificationSettingsTestTags.EXPIRED_REPEAT_SWITCH).performClick()

    assertEquals(listOf<Int?>(ExpirationPreferences.DEFAULT_REPEAT_DAYS), soonRepeatChanges)
    assertEquals(listOf<Int?>(ExpirationPreferences.DEFAULT_REPEAT_DAYS), expiredRepeatChanges)
  }

  @Test
  fun turningARepeatOffReportsNever() {
    show(preferences(soonRepeatDays = 7, expiredRepeatDays = 7))

    tag(NotificationSettingsTestTags.SOON_REPEAT_SWITCH).assertIsOn()
    tag(NotificationSettingsTestTags.SOON_REPEAT_SWITCH).performClick()

    assertEquals(1, soonRepeatChanges.size)
    assertNull(soonRepeatChanges.single())
    assertEquals(emptyList<Int?>(), expiredRepeatChanges)
  }

  @Test
  fun repeatIntervalIsBoundedToThirtyDays() {
    show(
        preferences(
            soonRepeatDays = ExpirationPreferences.REPEAT_DAYS_RANGE.last,
            expiredRepeatDays = ExpirationPreferences.REPEAT_DAYS_RANGE.first))

    tag(NotificationSettingsTestTags.SOON_REPEAT_INCREASE).assertIsNotEnabled()
    tag(NotificationSettingsTestTags.SOON_REPEAT_DECREASE).assertIsEnabled()
    tag(NotificationSettingsTestTags.EXPIRED_REPEAT_DECREASE).assertIsNotEnabled()
    tag(NotificationSettingsTestTags.EXPIRED_REPEAT_INCREASE).assertIsEnabled()
  }

  @Test
  fun saveErrorIsShownOnlyWhenTheLastChangeFailed() {
    show()
    composeTestRule.onNodeWithTag(NotificationSettingsTestTags.SAVE_ERROR).assertDoesNotExist()

    state = state.copy(saveFailed = true)

    composeTestRule
        .onNodeWithText(string(R.string.notification_settings_save_failed))
        .performScrollTo()
        .assertIsDisplayed()
  }

  @Test
  fun valueUpdatesWhenTheStateChangesAfterAStep() {
    show(preferences(soonDays = 59))

    tag(NotificationSettingsTestTags.SOON_INCREASE).performClick()
    state = preferences(soonDays = soonChanges.last())

    tag(NotificationSettingsTestTags.SOON_VALUE).assertTextEquals(days(60))
    tag(NotificationSettingsTestTags.SOON_INCREASE).assertIsNotEnabled()
  }
}
