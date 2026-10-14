// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.sharemate.R
import com.android.sharemate.ui.household.LeaveHouseholdError
import com.android.sharemate.ui.household.LeaveHouseholdUiState
import com.android.sharemate.ui.navigation.NavigationTestTags

/** Test tags used by [SettingsScreen]. */
object SettingsTestTags {
  const val LEAVE_HOUSEHOLD = "settings_leave_household"
  const val SIGN_OUT = "settings_sign_out"
  const val LEAVE_DIALOG = "settings_leave_dialog"
  const val LEAVE_CONFIRM = "settings_leave_confirm"
  const val LEAVE_CANCEL = "settings_leave_cancel"
  const val LEAVE_LOADING = "settings_leave_loading"
  const val LEAVE_ERROR = "settings_leave_error"
}

/**
 * Settings of the signed-in user: leaving the current household and signing out.
 *
 * @param householdName Name of the user's household, shown in the leave confirmation.
 * @param leaveState State of the leave confirmation.
 * @param onLeft Called once after the user has left the household.
 */
@Composable
fun SettingsScreen(
    householdName: String,
    leaveState: LeaveHouseholdUiState,
    onLeaveHousehold: () -> Unit,
    onCancelLeave: () -> Unit,
    onConfirmLeave: () -> Unit,
    onLeft: () -> Unit,
    onSignOut: () -> Unit,
) {
  LaunchedEffect(leaveState.left) { if (leaveState.left) onLeft() }
  Column(
      modifier = Modifier.fillMaxSize(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
  ) {
    Text(
        text = stringResource(R.string.settings_title),
        modifier = Modifier.testTag(NavigationTestTags.PAGE_CONTENT),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    TextButton(
        onClick = onLeaveHousehold,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.testTag(SettingsTestTags.LEAVE_HOUSEHOLD),
    ) {
      Text(stringResource(R.string.leave_household))
    }
    TextButton(onClick = onSignOut, modifier = Modifier.testTag(SettingsTestTags.SIGN_OUT)) {
      Text(stringResource(R.string.auth_sign_out))
    }
  }
  if (leaveState.isConfirming) {
    LeaveHouseholdDialog(householdName, leaveState, onCancelLeave, onConfirmLeave)
  }
}

@Composable
private fun LeaveHouseholdDialog(
    householdName: String,
    state: LeaveHouseholdUiState,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  AlertDialog(
      modifier = Modifier.testTag(SettingsTestTags.LEAVE_DIALOG),
      onDismissRequest = { if (!state.isLeaving) onCancel() },
      title = { Text(stringResource(R.string.leave_household_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Text(stringResource(R.string.leave_household_message, householdName))
          state.error?.let { error ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                  imageVector = Icons.Filled.Error,
                  contentDescription = null,
                  tint = colors.error,
                  modifier = Modifier.size(18.dp),
              )
              Text(
                  text =
                      stringResource(
                          when (error) {
                            LeaveHouseholdError.NO_SESSION -> R.string.leave_household_no_session
                            LeaveHouseholdError.LEAVE_FAILED -> R.string.leave_household_failed
                          }),
                  style = MaterialTheme.typography.labelLarge,
                  color = colors.error,
                  modifier = Modifier.testTag(SettingsTestTags.LEAVE_ERROR),
              )
            }
          }
        }
      },
      confirmButton = {
        if (state.isLeaving) {
          CircularProgressIndicator(
              modifier = Modifier.size(24.dp).testTag(SettingsTestTags.LEAVE_LOADING),
              strokeWidth = 2.dp,
          )
        } else {
          TextButton(
              onClick = onConfirm,
              colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
              modifier = Modifier.testTag(SettingsTestTags.LEAVE_CONFIRM),
          ) {
            Text(
                stringResource(
                    if (state.error != null) R.string.household_retry else R.string.leave))
          }
        }
      },
      dismissButton = {
        TextButton(
            onClick = onCancel,
            enabled = !state.isLeaving,
            modifier = Modifier.testTag(SettingsTestTags.LEAVE_CANCEL),
        ) {
          Text(stringResource(R.string.cancel))
        }
      },
  )
}
