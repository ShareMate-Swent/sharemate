// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.android.sharemate.R
import com.android.sharemate.model.user.ExpirationPreferences
import com.android.sharemate.notifications.NotificationPermission
import com.android.sharemate.ui.theme.Dimens

/** Test tags used by [NotificationSettingsScreen]. */
object NotificationSettingsTestTags {
  /** The row in Settings that opens this screen. */
  const val OPEN_ENTRY = "notification_settings_entry"
  /** The back arrow of the screen. */
  const val BACK = "notification_settings_back"
  /** The title of the screen. */
  const val TITLE = "notification_settings_title"
  /** The card about the phone's notification permission. */
  const val SYSTEM_CARD = "notification_settings_system_card"
  /** The text telling whether the phone allows notifications. */
  const val SYSTEM_STATUS = "notification_settings_system_status"
  /** The button that opens the phone's notification settings. */
  const val OPEN_SYSTEM_SETTINGS = "notification_settings_open_system"
  /** The card holding the "expiring soon" window. */
  const val SOON_CARD = "notification_settings_soon_card"
  /** The number of days of the "expiring soon" window. */
  const val SOON_VALUE = "notification_settings_soon_value"
  /** The button that lengthens the "expiring soon" window. */
  const val SOON_INCREASE = "notification_settings_soon_increase"
  /** The button that shortens the "expiring soon" window. */
  const val SOON_DECREASE = "notification_settings_soon_decrease"
  /** The card holding both reminder frequencies. */
  const val FREQUENCY_CARD = "notification_settings_frequency_card"
  /** The switch turning the repeated reminder on or off while expiring soon. */
  const val SOON_REPEAT_SWITCH = "notification_settings_soon_repeat_switch"
  /** The days between two reminders while expiring soon. */
  const val SOON_REPEAT_VALUE = "notification_settings_soon_repeat_value"
  /** The button that lengthens the repeat while expiring soon. */
  const val SOON_REPEAT_INCREASE = "notification_settings_soon_repeat_increase"
  /** The button that shortens the repeat while expiring soon. */
  const val SOON_REPEAT_DECREASE = "notification_settings_soon_repeat_decrease"
  /** The switch turning the repeated reminder on or off once expired. */
  const val EXPIRED_REPEAT_SWITCH = "notification_settings_expired_repeat_switch"
  /** The days between two reminders once expired. */
  const val EXPIRED_REPEAT_VALUE = "notification_settings_expired_repeat_value"
  /** The button that lengthens the repeat once expired. */
  const val EXPIRED_REPEAT_INCREASE = "notification_settings_expired_repeat_increase"
  /** The button that shortens the repeat once expired. */
  const val EXPIRED_REPEAT_DECREASE = "notification_settings_expired_repeat_decrease"
  /** The message shown when a change could not be saved. */
  const val SAVE_ERROR = "notification_settings_save_error"
  /** The message shown when the saved settings could not be read. */
  const val LOAD_ERROR = "notification_settings_load_error"
  /** The button that reads the saved settings again after a failure. */
  const val RETRY = "notification_settings_retry"
}

private val CardShape = RoundedCornerShape(SettingsLayout.cardCornerRadius)

/**
 * The notification settings sub-page of Settings: the system permission, when food counts as
 * "expiring soon" and how often to be reminded.
 *
 * @param isSystemAllowed Whether the system currently lets the app show notifications.
 * @param onRetryLoad Asked when the user taps "Retry" after the saved settings could not be read.
 */
@Composable
fun NotificationSettingsScreen(
    state: NotificationSettingsUiState,
    isSystemAllowed: Boolean,
    onBack: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onSoonDaysChange: (Int) -> Unit,
    onSoonRepeatChange: (Int?) -> Unit,
    onExpiredRepeatChange: (Int?) -> Unit,
    onRetryLoad: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxSize()) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spacingXs),
        verticalAlignment = Alignment.CenterVertically) {
          IconButton(
              onClick = onBack, modifier = Modifier.testTag(NotificationSettingsTestTags.BACK)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.notification_settings_back))
              }
          Text(
              text = stringResource(R.string.notification_settings_title),
              style = MaterialTheme.typography.titleLarge,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.testTag(NotificationSettingsTestTags.TITLE))
        }
    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SettingsLayout.horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingM)) {
          Spacer(Modifier.height(Dimens.spacingXs))
          SystemPermissionCard(isSystemAllowed, onOpenSystemSettings)
          if (state.isLoaded) {
            SoonCard(state.preferences, onSoonDaysChange)
            FrequencyCard(state.preferences, onSoonRepeatChange, onExpiredRepeatChange)
          }
          if (state.loadFailed) {
            Text(
                text = stringResource(R.string.notification_settings_load_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(NotificationSettingsTestTags.LOAD_ERROR))
            Button(
                onClick = onRetryLoad,
                modifier = Modifier.testTag(NotificationSettingsTestTags.RETRY)) {
                  Text(stringResource(R.string.notification_settings_retry))
                }
          }
          if (state.saveFailed) {
            Text(
                text = stringResource(R.string.notification_settings_save_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(NotificationSettingsTestTags.SAVE_ERROR))
          }
          Spacer(Modifier.height(Dimens.spacingL))
        }
  }
}

@Composable
private fun SettingsCard(tag: String, content: @Composable () -> Unit) {
  val colors = MaterialTheme.colorScheme
  Card(
      shape = CardShape,
      colors = CardDefaults.cardColors(containerColor = colors.surface),
      border = BorderStroke(Dimens.borderWidth, colors.outlineVariant),
      modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(
            Modifier.padding(Dimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingS)) {
              content()
            }
      }
}

@Composable
private fun SystemPermissionCard(isAllowed: Boolean, onOpenSystemSettings: () -> Unit) {
  SettingsCard(NotificationSettingsTestTags.SYSTEM_CARD) {
    Text(
        text = stringResource(R.string.notification_settings_system_title),
        style = MaterialTheme.typography.titleMedium)
    Text(
        text =
            stringResource(
                if (isAllowed) R.string.notification_settings_system_allowed
                else R.string.notification_settings_system_blocked),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(NotificationSettingsTestTags.SYSTEM_STATUS))
    Button(
        onClick = onOpenSystemSettings,
        modifier =
            Modifier.fillMaxWidth()
                .height(Dimens.buttonHeight)
                .testTag(NotificationSettingsTestTags.OPEN_SYSTEM_SETTINGS)) {
          Text(stringResource(R.string.notification_settings_open_system))
        }
  }
}

@Composable
private fun SoonCard(preferences: ExpirationPreferences, onSoonDaysChange: (Int) -> Unit) {
  SettingsCard(NotificationSettingsTestTags.SOON_CARD) {
    Text(
        text = stringResource(R.string.notification_settings_soon_title),
        style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(R.string.notification_settings_soon_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    DaysStepper(
        value = preferences.soonDays,
        range = ExpirationPreferences.SOON_DAYS_RANGE,
        onChange = onSoonDaysChange,
        valueTag = NotificationSettingsTestTags.SOON_VALUE,
        increaseTag = NotificationSettingsTestTags.SOON_INCREASE,
        decreaseTag = NotificationSettingsTestTags.SOON_DECREASE)
  }
}

@Composable
private fun FrequencyCard(
    preferences: ExpirationPreferences,
    onSoonRepeatChange: (Int?) -> Unit,
    onExpiredRepeatChange: (Int?) -> Unit,
) {
  SettingsCard(NotificationSettingsTestTags.FREQUENCY_CARD) {
    Text(
        text = stringResource(R.string.notification_settings_frequency_title),
        style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(R.string.notification_settings_frequency_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    RepeatSetting(
        label = stringResource(R.string.notification_settings_soon_repeat),
        value = preferences.soonRepeatDays,
        onChange = onSoonRepeatChange,
        switchTag = NotificationSettingsTestTags.SOON_REPEAT_SWITCH,
        valueTag = NotificationSettingsTestTags.SOON_REPEAT_VALUE,
        increaseTag = NotificationSettingsTestTags.SOON_REPEAT_INCREASE,
        decreaseTag = NotificationSettingsTestTags.SOON_REPEAT_DECREASE)
    RepeatSetting(
        label = stringResource(R.string.notification_settings_expired_repeat),
        value = preferences.expiredRepeatDays,
        onChange = onExpiredRepeatChange,
        switchTag = NotificationSettingsTestTags.EXPIRED_REPEAT_SWITCH,
        valueTag = NotificationSettingsTestTags.EXPIRED_REPEAT_VALUE,
        increaseTag = NotificationSettingsTestTags.EXPIRED_REPEAT_INCREASE,
        decreaseTag = NotificationSettingsTestTags.EXPIRED_REPEAT_DECREASE)
  }
}

/** A switch for "repeat or never", and the number of days between reminders while it is on. */
@Composable
private fun RepeatSetting(
    label: String,
    value: Int?,
    onChange: (Int?) -> Unit,
    switchTag: String,
    valueTag: String,
    increaseTag: String,
    decreaseTag: String,
) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    Switch(
        checked = value != null,
        onCheckedChange = { on ->
          onChange(if (on) ExpirationPreferences.DEFAULT_REPEAT_DAYS else null)
        },
        modifier = Modifier.testTag(switchTag))
  }
  if (value != null) {
    DaysStepper(
        value = value,
        range = ExpirationPreferences.REPEAT_DAYS_RANGE,
        onChange = onChange,
        valueTag = valueTag,
        increaseTag = increaseTag,
        decreaseTag = decreaseTag)
  }
}

/** Picks a whole number of days within [range] with minus and plus buttons. */
@Composable
private fun DaysStepper(
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    valueTag: String,
    increaseTag: String,
    decreaseTag: String,
) {
  Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onChange(value - 1) },
            enabled = value > range.first,
            modifier = Modifier.testTag(decreaseTag)) {
              Icon(
                  Icons.Filled.Remove,
                  contentDescription = stringResource(R.string.notification_settings_decrease))
            }
        Text(
            text = pluralStringResource(R.plurals.notification_settings_days, value, value),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = Dimens.spacingM).testTag(valueTag))
        IconButton(
            onClick = { onChange(value + 1) },
            enabled = value < range.last,
            modifier = Modifier.testTag(increaseTag)) {
              Icon(
                  Icons.Filled.Add,
                  contentDescription = stringResource(R.string.notification_settings_increase))
            }
      }
}

/** Whether the system currently lets the app post notifications, refreshed when the app resumes. */
@Composable
fun rememberSystemNotificationsAllowed(): Boolean {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  var allowed by remember { mutableStateOf(areNotificationsAllowed(context)) }
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) allowed = areNotificationsAllowed(context)
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  return allowed
}

private fun areNotificationsAllowed(context: Context): Boolean =
    !NotificationPermission.shouldRequest(context) &&
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

/** Opens the phone's own notification settings for this app. */
fun openSystemNotificationSettings(context: Context) {
  val intent =
      Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
          .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  context.startActivity(intent)
}
