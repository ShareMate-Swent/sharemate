// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.android.sharemate.R
import com.android.sharemate.model.auth.SignInProvider
import com.android.sharemate.ui.household.LeaveHouseholdError
import com.android.sharemate.ui.household.LeaveHouseholdUiState
import com.android.sharemate.ui.navigation.NavigationTestTags

/** Test tags used by [SettingsScreen]. */
object SettingsTestTags {
  const val PROFILE_PHOTO = "settings_profile_photo"
  const val PROFILE_PLACEHOLDER = "settings_profile_placeholder"
  const val PROFILE_NAME = "settings_profile_name"
  const val PROFILE_EMAIL = "settings_profile_email"
  const val ACCOUNT_CARD = "settings_account_card"
  const val ACCOUNT_TITLE = "settings_account_title"
  const val ACCOUNT_STATUS = "settings_account_status"
  const val SWITCH_GOOGLE_ACCOUNT = "settings_switch_google_account"
  const val SWITCH_LOADING = "settings_switch_loading"
  const val ACCOUNT_ERROR = "settings_account_error"
  const val LEAVE_HOUSEHOLD = "settings_leave_household"
  const val SIGN_OUT = "settings_sign_out"
  const val LEAVE_DIALOG = "settings_leave_dialog"
  const val LEAVE_CONFIRM = "settings_leave_confirm"
  const val LEAVE_CANCEL = "settings_leave_cancel"
  const val LEAVE_LOADING = "settings_leave_loading"
  const val LEAVE_ERROR = "settings_leave_error"
}

private val CardShape = RoundedCornerShape(24.dp)

/**
 * Settings of the signed-in user: their profile, their account and leaving the household.
 *
 * @param state Profile, account and household shown on the screen.
 * @param leaveState State of the leave confirmation.
 * @param onSwitchGoogleAccount Opens the Google account picker; shown only for Google accounts.
 * @param onLeft Called once after the user has left the household.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    leaveState: LeaveHouseholdUiState,
    onLeaveHousehold: () -> Unit,
    onCancelLeave: () -> Unit,
    onConfirmLeave: () -> Unit,
    onLeft: () -> Unit,
    onSwitchGoogleAccount: () -> Unit,
    onSignOut: () -> Unit,
) {
  LaunchedEffect(leaveState.left) { if (leaveState.left) onLeft() }
  Column(
      modifier =
          Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(Modifier.height(24.dp))
    Text(
        text = stringResource(R.string.settings_title),
        modifier = Modifier.testTag(NavigationTestTags.PAGE_CONTENT),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(28.dp))
    ProfileHeader(state)
    Spacer(Modifier.height(24.dp))
    AccountCard(state, onSwitchGoogleAccount, onSignOut)
    // Moves into the Household card in task 21.4.
    TextButton(
        onClick = onLeaveHousehold,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.padding(vertical = 16.dp).testTag(SettingsTestTags.LEAVE_HOUSEHOLD),
    ) {
      Text(stringResource(R.string.leave_household))
    }
  }
  if (leaveState.isConfirming) {
    LeaveHouseholdDialog(state.householdName, leaveState, onCancelLeave, onConfirmLeave)
  }
}

/** Photo, name and email. Without a name, the email takes its place and is not repeated. */
@Composable
private fun ProfileHeader(state: SettingsUiState) {
  Avatar(state.photoUrl)
  Spacer(Modifier.height(16.dp))
  Text(
      text = state.displayName ?: state.email.orEmpty(),
      style = MaterialTheme.typography.headlineSmall,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
      modifier = Modifier.testTag(SettingsTestTags.PROFILE_NAME),
  )
  if (state.displayName != null && state.email != null) {
    Spacer(Modifier.height(4.dp))
    Text(
        text = state.email,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag(SettingsTestTags.PROFILE_EMAIL),
    )
  }
}

/** The account photo, drawn over a placeholder shown while it loads, if it fails or if none. */
@Composable
private fun Avatar(photoUrl: String?) {
  val painter = photoUrl?.let { rememberAsyncImagePainter(model = it) }
  Box(
      modifier =
          Modifier.size(72.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primaryContainer),
      contentAlignment = Alignment.Center,
  ) {
    if (painter?.state !is AsyncImagePainter.State.Success) {
      Icon(
          imageVector = Icons.Filled.Person,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.size(40.dp).testTag(SettingsTestTags.PROFILE_PLACEHOLDER),
      )
    }
    if (painter != null) {
      Image(
          painter = painter,
          contentDescription = stringResource(R.string.settings_profile_photo),
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize().testTag(SettingsTestTags.PROFILE_PHOTO),
      )
    }
  }
}

/** How the user is signed in, with Sign out and, for Google accounts, Switch Google account. */
@Composable
private fun AccountCard(
    state: SettingsUiState,
    onSwitchGoogleAccount: () -> Unit,
    onSignOut: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  // Accounts signed in another way are shown like email accounts, without a switch action.
  val isGoogle = state.provider == SignInProvider.GOOGLE
  Card(
      shape = CardShape,
      colors = CardDefaults.cardColors(containerColor = colors.surface),
      border = BorderStroke(1.dp, colors.outlineVariant),
      modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.ACCOUNT_CARD),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(
          horizontalArrangement = Arrangement.spacedBy(16.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        AccountIcon(if (isGoogle) Icons.Outlined.AccountCircle else Icons.Outlined.Email)
        Column {
          Text(
              text =
                  stringResource(
                      if (isGoogle) R.string.settings_google_account
                      else R.string.settings_email_account),
              style = MaterialTheme.typography.titleMedium,
              color = colors.onSurface,
              modifier = Modifier.testTag(SettingsTestTags.ACCOUNT_TITLE),
          )
          Text(
              text =
                  stringResource(
                      if (isGoogle) R.string.settings_connected
                      else R.string.settings_signed_in_with_email),
              style = MaterialTheme.typography.bodyMedium,
              color = colors.onSurfaceVariant,
              modifier = Modifier.testTag(SettingsTestTags.ACCOUNT_STATUS),
          )
        }
      }
      Text(
          text =
              stringResource(
                  if (isGoogle) R.string.settings_google_description
                  else R.string.settings_email_description),
          style = MaterialTheme.typography.bodyMedium,
          color = colors.onSurfaceVariant,
      )
      if (isGoogle) {
        Button(
            onClick = onSwitchGoogleAccount,
            enabled = !state.isSwitchingAccount,
            modifier =
                Modifier.fillMaxWidth()
                    .height(48.dp)
                    .testTag(SettingsTestTags.SWITCH_GOOGLE_ACCOUNT),
        ) {
          if (state.isSwitchingAccount) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp).testTag(SettingsTestTags.SWITCH_LOADING),
                strokeWidth = 2.dp,
            )
          } else {
            Text(stringResource(R.string.settings_switch_google_account))
          }
        }
        state.accountError?.let { AccountError(it) }
      }
      OutlinedButton(
          onClick = onSignOut,
          enabled = !state.isSwitchingAccount,
          modifier = Modifier.fillMaxWidth().height(48.dp).testTag(SettingsTestTags.SIGN_OUT),
      ) {
        Text(stringResource(R.string.auth_sign_out))
      }
    }
  }
}

@Composable
private fun AccountIcon(icon: ImageVector) {
  Box(
      modifier =
          Modifier.size(44.dp)
              .clip(RoundedCornerShape(12.dp))
              .background(MaterialTheme.colorScheme.primaryContainer),
      contentAlignment = Alignment.Center,
  ) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimaryContainer,
    )
  }
}

@Composable
private fun AccountError(error: SettingsAccountError) {
  Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
        imageVector = Icons.Filled.Error,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(18.dp),
    )
    Text(
        text =
            stringResource(
                when (error) {
                  SettingsAccountError.GOOGLE_UNAVAILABLE -> R.string.settings_switch_unavailable
                  SettingsAccountError.NETWORK -> R.string.settings_switch_network
                  SettingsAccountError.SWITCH_FAILED -> R.string.settings_switch_failed
                }),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(SettingsTestTags.ACCOUNT_ERROR),
    )
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
