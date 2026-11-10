// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: OpenAI Codex
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.android.sharemate.R
import com.android.sharemate.model.auth.SignInProvider
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.ui.household.CopyInviteCodeButton
import com.android.sharemate.ui.household.LeaveHouseholdError
import com.android.sharemate.ui.household.LeaveHouseholdUiState
import com.android.sharemate.ui.navigation.NavigationTestTags
import com.android.sharemate.ui.profile.ProfileAvatar
import com.android.sharemate.ui.theme.Dimens

/** Test tags used by [SettingsScreen]. */
object SettingsTestTags {
  const val PROFILE_PHOTO = "settings_profile_photo"
  const val PROFILE_PLACEHOLDER = "settings_profile_placeholder"
  const val PROFILE_NAME = "settings_profile_name"
  const val PROFILE_EMAIL = "settings_profile_email"
  const val PSEUDO_CARD = "settings_pseudo_card"
  const val PSEUDO_FIELD = "settings_pseudo_field"
  const val PSEUDO_SAVE = "settings_pseudo_save"
  const val PSEUDO_LOADING = "settings_pseudo_loading"
  const val PSEUDO_ERROR = "settings_pseudo_error"
  const val PSEUDO_RETRY = "settings_pseudo_retry"
  const val ACCOUNT_CARD = "settings_account_card"
  const val ACCOUNT_TITLE = "settings_account_title"
  const val ACCOUNT_STATUS = "settings_account_status"
  const val SWITCH_GOOGLE_ACCOUNT = "settings_switch_google_account"
  const val SWITCH_LOADING = "settings_switch_loading"
  const val ACCOUNT_ERROR = "settings_account_error"
  const val HOUSEHOLD_CARD = "settings_household_card"
  const val HOUSEHOLD_NAME = "settings_household_name"
  const val HOUSEHOLD_MEMBERS = "settings_household_members"
  const val SHOW_MEMBERS = "settings_show_members"
  const val INVITE_CODE = "settings_invite_code"
  const val COPY_INVITE_CODE = "settings_copy_invite_code"
  const val LEAVE_HOUSEHOLD = "settings_leave_household"
  const val SIGN_OUT = "settings_sign_out"
  const val LEAVE_DIALOG = "settings_leave_dialog"
  const val LEAVE_CONFIRM = "settings_leave_confirm"
  const val LEAVE_CANCEL = "settings_leave_cancel"
  const val LEAVE_LOADING = "settings_leave_loading"
  const val LEAVE_ERROR = "settings_leave_error"
}

private val CardShape = RoundedCornerShape(SettingsLayout.cardCornerRadius)

/**
 * Settings of the signed-in user: their profile, their account and their household.
 *
 * @param state Profile, account and household shown on the screen.
 * @param leaveState State of the leave confirmation.
 * @param onLeaveHousehold Requests to leave the current household.
 * @param onCancelLeave Closes the leave confirmation.
 * @param onConfirmLeave Confirms the requested household departure.
 * @param onLeft Called once after the user has left the household.
 * @param onShowMembers Opens the household members list.
 * @param onSwitchGoogleAccount Opens the Google account picker; shown only for Google accounts.
 * @param onPseudoChanged Updates the pseudo being edited.
 * @param onSavePseudo Saves the edited pseudo as the public profile name.
 * @param onRetryLoadPseudo Loads the saved pseudo again after a load failure.
 * @param onSignOut Signs out the current user.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    leaveState: LeaveHouseholdUiState,
    onLeaveHousehold: () -> Unit,
    onCancelLeave: () -> Unit,
    onConfirmLeave: () -> Unit,
    onLeft: () -> Unit,
    onShowMembers: () -> Unit,
    onSwitchGoogleAccount: () -> Unit,
    onPseudoChanged: (String) -> Unit,
    onSavePseudo: () -> Unit,
    onRetryLoadPseudo: () -> Unit,
    onSignOut: () -> Unit,
) {
  LaunchedEffect(leaveState.left) { if (leaveState.left) onLeft() }
  Column(
      modifier =
          Modifier.fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(horizontal = SettingsLayout.horizontalPadding),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(Modifier.height(Dimens.spacingL))
    Text(
        text = stringResource(R.string.settings_title),
        modifier = Modifier.testTag(NavigationTestTags.PAGE_CONTENT),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(SettingsLayout.sectionSpacing))
    ProfileHeader(state)
    Spacer(Modifier.height(Dimens.spacingL))
    PseudoCard(state, onPseudoChanged, onSavePseudo, onRetryLoadPseudo)
    Spacer(Modifier.height(Dimens.spacingL))
    AccountCard(state, onSwitchGoogleAccount, onSignOut)
    Spacer(Modifier.height(Dimens.spacingM))
    HouseholdCard(state, onShowMembers, onLeaveHousehold)
    Spacer(Modifier.height(Dimens.spacingL))
  }
  if (leaveState.isConfirming) {
    LeaveHouseholdDialog(
        state.householdName,
        state.fridgeType == FridgeType.PRIVATE,
        leaveState,
        onCancelLeave,
        onConfirmLeave)
  }
}

@Composable
private fun PseudoCard(
    state: SettingsUiState,
    onPseudoChanged: (String) -> Unit,
    onSavePseudo: () -> Unit,
    onRetryLoadPseudo: () -> Unit,
) {
  val loadFailed = state.pseudoError == SettingsPseudoError.LOAD_FAILED
  Card(
      shape = CardShape,
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      border = BorderStroke(Dimens.borderWidth, MaterialTheme.colorScheme.outlineVariant),
      modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.PSEUDO_CARD),
  ) {
    Column(
        Modifier.padding(Dimens.cardPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingS),
    ) {
      Text(
          text = stringResource(R.string.settings_pseudo_title),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
      )
      OutlinedTextField(
          value = state.pseudo,
          onValueChange = onPseudoChanged,
          enabled = !state.isLoadingPseudo && !state.isSavingPseudo && !loadFailed,
          label = { Text(stringResource(R.string.settings_pseudo_label)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.PSEUDO_FIELD),
      )
      if (state.isLoadingPseudo) {
        Text(
            text = stringResource(R.string.settings_pseudo_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(SettingsTestTags.PSEUDO_LOADING),
        )
      }
      state.pseudoError?.let { error ->
        Text(
            text =
                stringResource(
                    when (error) {
                      SettingsPseudoError.LOAD_FAILED -> R.string.settings_pseudo_load_failed
                      SettingsPseudoError.SAVE_FAILED -> R.string.settings_pseudo_save_failed
                    }),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(SettingsTestTags.PSEUDO_ERROR),
        )
      }
      if (loadFailed) {
        OutlinedButton(
            onClick = onRetryLoadPseudo,
            modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.PSEUDO_RETRY),
        ) {
          Text(stringResource(R.string.household_retry))
        }
      }
      Button(
          onClick = onSavePseudo,
          enabled =
              !loadFailed &&
                  !state.isLoadingPseudo &&
                  !state.isSavingPseudo &&
                  state.pseudo.trim().isNotEmpty() &&
                  state.pseudo.trim() != state.savedPseudo,
          modifier =
              Modifier.fillMaxWidth()
                  .height(Dimens.buttonHeight)
                  .testTag(SettingsTestTags.PSEUDO_SAVE),
      ) {
        if (state.isSavingPseudo) {
          CircularProgressIndicator(
              modifier = Modifier.size(Dimens.progressSize),
              strokeWidth = Dimens.progressStrokeWidth,
          )
        } else {
          Text(stringResource(R.string.settings_pseudo_save))
        }
      }
    }
  }
}

/** Photo, name and email. Without a name, the email takes its place and is not repeated. */
@Composable
private fun ProfileHeader(state: SettingsUiState) {
  ProfileAvatar(
      photoUrl = state.photoUrl,
      size = SettingsLayout.avatarSize,
      iconSize = SettingsLayout.avatarIconSize,
      contentDescription = stringResource(R.string.settings_profile_photo),
      photoTestTag = SettingsTestTags.PROFILE_PHOTO,
      placeholderTestTag = SettingsTestTags.PROFILE_PLACEHOLDER,
  )
  Spacer(Modifier.height(Dimens.spacingM))
  Text(
      text = state.displayName ?: state.email.orEmpty(),
      style = MaterialTheme.typography.headlineSmall,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
      modifier = Modifier.testTag(SettingsTestTags.PROFILE_NAME),
  )
  if (state.displayName != null && state.email != null) {
    Spacer(Modifier.height(Dimens.spacingXxs))
    Text(
        text = state.email,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag(SettingsTestTags.PROFILE_EMAIL),
    )
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
      border = BorderStroke(Dimens.borderWidth, colors.outlineVariant),
      modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.ACCOUNT_CARD),
  ) {
    Column(
        Modifier.padding(Dimens.cardPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingS)) {
          Row(
              horizontalArrangement = Arrangement.spacedBy(Dimens.spacingM),
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
                        .height(Dimens.buttonHeight)
                        .testTag(SettingsTestTags.SWITCH_GOOGLE_ACCOUNT),
            ) {
              if (state.isSwitchingAccount) {
                CircularProgressIndicator(
                    modifier =
                        Modifier.size(Dimens.progressSize).testTag(SettingsTestTags.SWITCH_LOADING),
                    strokeWidth = Dimens.progressStrokeWidth,
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
              modifier =
                  Modifier.fillMaxWidth()
                      .height(Dimens.buttonHeight)
                      .testTag(SettingsTestTags.SIGN_OUT),
          ) {
            Text(stringResource(R.string.auth_sign_out))
          }
        }
  }
}

/**
 * The household's name, member count and invite code, with Leave household. Tapping the name and
 * member count opens the members list. Private households cannot be joined by anyone else or left,
 * so their invite code is not shown.
 */
@Composable
private fun HouseholdCard(
    state: SettingsUiState,
    onShowMembers: () -> Unit,
    onLeaveHousehold: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  Card(
      shape = CardShape,
      colors = CardDefaults.cardColors(containerColor = colors.surface),
      border = BorderStroke(Dimens.borderWidth, colors.outlineVariant),
      modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.HOUSEHOLD_CARD),
  ) {
    Column(
        Modifier.padding(Dimens.cardPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingS)) {
          Text(
              text = stringResource(R.string.settings_household),
              style = MaterialTheme.typography.titleMedium,
              color = colors.onSurface,
          )
          // The whole row is the touch target, so the member count is easy to tap.
          Row(
              horizontalArrangement = Arrangement.spacedBy(Dimens.spacingM),
              verticalAlignment = Alignment.CenterVertically,
              modifier =
                  Modifier.fillMaxWidth()
                      .clip(RoundedCornerShape(SettingsLayout.cardIconBoxCornerRadius))
                      .clickable(
                          onClickLabel = stringResource(R.string.settings_show_members),
                          role = Role.Button,
                          onClick = onShowMembers)
                      .testTag(SettingsTestTags.SHOW_MEMBERS),
          ) {
            AccountIcon(Icons.Outlined.Home)
            Column(Modifier.weight(1f)) {
              Text(
                  text = state.householdName,
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  color = colors.onSurface,
                  modifier = Modifier.testTag(SettingsTestTags.HOUSEHOLD_NAME),
              )
              Text(
                  text =
                      pluralStringResource(
                          R.plurals.settings_members, state.memberCount, state.memberCount),
                  style = MaterialTheme.typography.bodyMedium,
                  color = colors.onSurfaceVariant,
                  modifier = Modifier.testTag(SettingsTestTags.HOUSEHOLD_MEMBERS),
              )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
            )
          }
          if (state.fridgeType != FridgeType.PRIVATE) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                  text = stringResource(R.string.settings_invite_code),
                  style = MaterialTheme.typography.bodyMedium,
                  color = colors.onSurfaceVariant,
                  modifier = Modifier.weight(1f),
              )
              Text(
                  text = state.inviteCode,
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  color = colors.onPrimaryContainer,
                  modifier = Modifier.testTag(SettingsTestTags.INVITE_CODE),
              )
              Spacer(Modifier.width(Dimens.spacingXs))
              CopyInviteCodeButton(
                  inviteCode = state.inviteCode,
                  modifier = Modifier.testTag(SettingsTestTags.COPY_INVITE_CODE),
              )
            }
          }
          OutlinedButton(
              onClick = onLeaveHousehold,
              enabled = state.fridgeType != FridgeType.PRIVATE,
              colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
              border = BorderStroke(Dimens.borderWidth, colors.error),
              modifier =
                  Modifier.fillMaxWidth()
                      .height(Dimens.buttonHeight)
                      .testTag(SettingsTestTags.LEAVE_HOUSEHOLD),
          ) {
            Text(stringResource(R.string.leave_household))
          }
          if (state.fridgeType == FridgeType.PRIVATE) {
            Text(
                stringResource(R.string.leave_private_household_blocked),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
          }
        }
  }
}

/** Icon in a rounded box, shown at the start of the Account and Household cards. */
@Composable
private fun AccountIcon(icon: ImageVector) {
  Box(
      modifier =
          Modifier.size(SettingsLayout.cardIconBoxSize)
              .clip(RoundedCornerShape(SettingsLayout.cardIconBoxCornerRadius))
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
      horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
        imageVector = Icons.Filled.Error,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(Dimens.iconSmall),
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
    privateFridge: Boolean,
    state: LeaveHouseholdUiState,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val leaveBlocked = privateFridge || state.error == LeaveHouseholdError.PRIVATE_HOUSEHOLD
  AlertDialog(
      modifier = Modifier.testTag(SettingsTestTags.LEAVE_DIALOG),
      onDismissRequest = { if (!state.isLeaving) onCancel() },
      title = { Text(stringResource(R.string.leave_household_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingM)) {
          Text(
              if (leaveBlocked) stringResource(R.string.leave_private_household_blocked)
              else stringResource(R.string.leave_household_message, householdName))
          state.error
              ?.takeUnless { leaveBlocked }
              ?.let { error ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                  Icon(
                      imageVector = Icons.Filled.Error,
                      contentDescription = null,
                      tint = colors.error,
                      modifier = Modifier.size(Dimens.iconSmall),
                  )
                  Text(
                      text =
                          stringResource(
                              when (error) {
                                LeaveHouseholdError.NO_SESSION ->
                                    R.string.leave_household_no_session
                                LeaveHouseholdError.PRIVATE_HOUSEHOLD ->
                                    R.string.leave_private_household_blocked
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
        if (state.isLeaving && !leaveBlocked) {
          CircularProgressIndicator(
              modifier =
                  Modifier.size(SettingsLayout.dialogProgressSize)
                      .testTag(SettingsTestTags.LEAVE_LOADING),
              strokeWidth = Dimens.progressStrokeWidth,
          )
        } else if (!leaveBlocked) {
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
