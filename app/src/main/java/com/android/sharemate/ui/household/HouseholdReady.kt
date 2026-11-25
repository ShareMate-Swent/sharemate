// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.household

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import com.android.sharemate.R
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.ui.components.AppCard
import com.android.sharemate.ui.components.AppPrimaryButton
import com.android.sharemate.ui.components.AppStepIndicator
import com.android.sharemate.ui.theme.Dimens

/** MIME type of the text shared through the system share sheet. */
private const val SHARE_MIME_TYPE = "text/plain"

/**
 * The view shown after a household is created: a confirmation and, for a shared household, its
 * invite code with copy and share actions.
 *
 * @param name The household name, shown above the invite code.
 * @param inviteCode The invite code of the new household; not shown for a private fridge.
 * @param type Whether the new fridge is private or shared.
 * @param onContinue Called when the user goes on to the fridge.
 */
@Composable
internal fun ColumnScope.HouseholdReady(
    name: String,
    inviteCode: String,
    type: FridgeType,
    onContinue: () -> Unit,
) {
  val isPrivate = type == FridgeType.PRIVATE
  AppStepIndicator(HouseholdLayout.READY_STEP, HouseholdLayout.ONBOARDING_STEP_COUNT)
  Spacer(Modifier.height(Dimens.spacingXxl))
  ReadyBadge(Modifier.align(Alignment.CenterHorizontally))
  Spacer(Modifier.height(Dimens.spacingL))
  Text(
      text =
          stringResource(
              if (isPrivate) R.string.private_fridge_ready_title
              else R.string.household_ready_title),
      style = MaterialTheme.typography.headlineLarge,
      textAlign = TextAlign.Center,
      modifier = Modifier.fillMaxWidth(),
  )
  Spacer(Modifier.height(Dimens.spacingS))
  Text(
      text =
          stringResource(
              if (isPrivate) R.string.private_fridge_ready_description
              else R.string.household_ready_description),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spacingM),
  )
  Spacer(Modifier.height(Dimens.spacingS))
  Text(
      text =
          stringResource(
              if (isPrivate) R.string.fridge_type_private else R.string.fridge_type_shared),
      style = MaterialTheme.typography.labelLarge,
      textAlign = TextAlign.Center,
      modifier = Modifier.fillMaxWidth(),
  )
  Spacer(Modifier.height(Dimens.spacingL))
  if (!isPrivate) InviteCodeCard(name, inviteCode)
  Spacer(Modifier.weight(1f))
  AppPrimaryButton(
      text = stringResource(R.string.go_to_my_fridge),
      onClick = onContinue,
      modifier = Modifier.testTag(CreateHouseholdTestTags.CONTINUE),
  )
}

/** The round check mark that confirms the household was created. */
@Composable
private fun ReadyBadge(modifier: Modifier = Modifier) {
  Box(
      modifier =
          modifier
              .size(HouseholdLayout.readyBadgeSize)
              .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
      contentAlignment = Alignment.Center,
  ) {
    Icon(
        imageVector = Icons.Filled.Check,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(HouseholdLayout.readyBadgeIconSize),
    )
  }
}

/** A card with the household name, its invite code and the copy and share actions. */
@Composable
private fun InviteCodeCard(name: String, inviteCode: String) {
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current
  AppCard {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.spacingL),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingM),
    ) {
      Text(
          text = stringResource(R.string.household_invite_code_label, name),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      InviteCodeCells(inviteCode)
      Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingS)) {
        InviteActionButton(
            icon = Icons.Outlined.ContentCopy,
            label = stringResource(R.string.copy_invite_code_action),
            onClick = { copyInviteCode(clipboard, inviteCode) },
            modifier = Modifier.weight(1f).testTag(CreateHouseholdTestTags.COPY_INVITE_CODE),
        )
        InviteActionButton(
            icon = Icons.Outlined.IosShare,
            label = stringResource(R.string.share_invite_code),
            onClick = { shareInviteCode(context, inviteCode) },
            modifier = Modifier.weight(1f).testTag(CreateHouseholdTestTags.SHARE_INVITE_CODE),
        )
      }
    }
  }
}

/**
 * The invite code drawn as one cell per character. Screen readers and tests see it as a single
 * text, the code itself.
 */
@Composable
private fun InviteCodeCells(inviteCode: String) {
  Row(
      modifier =
          Modifier.fillMaxWidth().clearAndSetSemantics { text = AnnotatedString(inviteCode) },
      horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs),
  ) {
    inviteCode.forEach { character ->
      Box(
          modifier =
              Modifier.weight(1f)
                  .height(HouseholdLayout.inviteCharHeight)
                  .background(
                      MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small),
          contentAlignment = Alignment.Center,
      ) {
        Text(text = character.toString(), style = MaterialTheme.typography.titleLarge)
      }
    }
  }
}

/** An outlined button with an icon and a label, used for the invite code actions. */
@Composable
private fun InviteActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  OutlinedButton(
      onClick = onClick,
      shape = MaterialTheme.shapes.medium,
      border = BorderStroke(Dimens.borderWidth, MaterialTheme.colorScheme.outlineVariant),
      modifier = modifier.height(Dimens.buttonHeight),
  ) {
    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(Dimens.iconSmall))
    Spacer(Modifier.size(Dimens.spacingXs))
    Text(text = label, style = MaterialTheme.typography.labelMedium)
  }
}

/** Opens the system share sheet with [inviteCode] as plain text. */
private fun shareInviteCode(context: Context, inviteCode: String) {
  val send =
      Intent(Intent.ACTION_SEND).setType(SHARE_MIME_TYPE).putExtra(Intent.EXTRA_TEXT, inviteCode)
  context.startActivity(Intent.createChooser(send, null))
}
