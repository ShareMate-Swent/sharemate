// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.settings

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SystemNotificationSettingsTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private val app = ApplicationProvider.getApplicationContext<Application>()

  private fun setSystemAllowed(allowed: Boolean) {
    val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    shadowOf(manager).setNotificationsEnabled(allowed)
  }

  private fun showStatus() {
    composeTestRule.setContent {
      Text(rememberSystemNotificationsAllowed().toString(), Modifier.testTag(TAG))
    }
  }

  @Test
  fun reportsAllowedWhenThePhoneLetsTheAppNotify() {
    setSystemAllowed(true)

    showStatus()

    composeTestRule.onNodeWithTag(TAG).assertTextEquals("true")
  }

  @Test
  fun reportsBlockedWhenTheUserTurnedNotificationsOffInPhoneSettings() {
    setSystemAllowed(false)

    showStatus()

    composeTestRule.onNodeWithTag(TAG).assertTextEquals("false")
  }

  @Test
  fun refreshesWhenTheUserComesBackFromPhoneSettings() {
    setSystemAllowed(false)
    showStatus()
    composeTestRule.onNodeWithTag(TAG).assertTextEquals("false")

    composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
    setSystemAllowed(true)
    composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

    composeTestRule.onNodeWithTag(TAG).assertTextEquals("true")
  }

  @Test
  fun opensTheAppNotificationPageOfThePhoneSettingsInANewTask() {
    openSystemNotificationSettings(app)

    val intent = shadowOf(app).nextStartedActivity
    assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
    assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    assertNotEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
  }

  private companion object {
    const val TAG = "system_allowed"
  }
}
