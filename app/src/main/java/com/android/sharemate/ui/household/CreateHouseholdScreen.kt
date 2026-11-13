// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.household

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.android.sharemate.R
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.text.TextLimits
import com.android.sharemate.ui.TextLengthCounter
import com.android.sharemate.ui.theme.Dimens

/** Test tags used by [CreateHouseholdScreen]. */
object CreateHouseholdTestTags {
  const val NAME = "create_household_name"
  const val CREATE = "create_household_button"
  const val BACK = "create_household_back"
  const val COPY_INVITE_CODE = "copy_invite_code"
  const val SHARE_INVITE_CODE = "share_invite_code"
  const val CONTINUE = "create_household_continue"
  const val LOADING = "create_household_loading"
  const val ERROR = "create_household_error"
  const val TYPE_PRIVATE = "create_household_type_private"
  const val TYPE_SHARED = "create_household_type_shared"
}

/** Screen for creating a private or shared household fridge. */
@Composable
fun CreateHouseholdScreen(
    viewModel: CreateHouseholdViewModel,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
  val state = viewModel.state.collectAsState().value
  Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(
                    horizontal = Dimens.screenHorizontalPadding,
                    vertical = Dimens.screenVerticalPadding),
    ) {
      if (state.inviteCode == null) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
          Column(
              modifier =
                  Modifier.fillMaxWidth()
                      .verticalScroll(rememberScrollState())
                      .heightIn(min = maxHeight),
          ) {
            CreateHouseholdForm(state, viewModel, onBack)
          }
        }
      } else {
        HouseholdReady(state.inviteCode, state.type, onContinue)
      }
    }
  }
}

/** Shared top bar with a back arrow, centered title and optional trailing [actions]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HouseholdTopBar(
    title: String,
    onBack: () -> Unit,
    backTestTag: String,
    actions: @Composable RowScope.() -> Unit = {},
) {
  CenterAlignedTopAppBar(
      title = { Text(title) },
      navigationIcon = {
        IconButton(onClick = onBack, modifier = Modifier.testTag(backTestTag)) {
          Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.back),
          )
        }
      },
      actions = actions,
      colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
      windowInsets = WindowInsets(0),
  )
}

@Composable
private fun ColumnScope.CreateHouseholdForm(
    state: CreateHouseholdUiState,
    viewModel: CreateHouseholdViewModel,
    onBack: () -> Unit,
) {
  HouseholdTopBar(
      title = stringResource(R.string.create_household_title),
      onBack = onBack,
      backTestTag = CreateHouseholdTestTags.BACK,
  )
  Spacer(Modifier.height(Dimens.spacingM))
  Text(
      text = stringResource(R.string.create_household_description),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Spacer(Modifier.height(Dimens.spacingM))
  OutlinedTextField(
      value = state.name,
      onValueChange = viewModel::updateName,
      label = { Text(stringResource(R.string.household_name)) },
      placeholder = { Text(stringResource(R.string.household_name_placeholder)) },
      supportingText = { TextLengthCounter(state.name, TextLimits.HOUSEHOLD_NAME) },
      modifier = Modifier.fillMaxWidth().testTag(CreateHouseholdTestTags.NAME),
      enabled = !state.isLoading && !state.hasCreationOperation,
      singleLine = true,
  )
  Spacer(Modifier.height(Dimens.spacingM))
  Text(stringResource(R.string.fridge_type), style = MaterialTheme.typography.titleSmall)
  Column(Modifier.selectableGroup()) {
    FridgeTypeOption(FridgeType.SHARED, state, viewModel::updateType)
    FridgeTypeOption(FridgeType.PRIVATE, state, viewModel::updateType)
  }
  state.error?.let {
    Spacer(Modifier.height(Dimens.spacingXs))
    Text(
        text = stringResource(R.string.household_generic_error),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(CreateHouseholdTestTags.ERROR),
    )
    if (state.hasCreationOperation) {
      Text(
          text = stringResource(R.string.create_household_change_request_hint),
          style = MaterialTheme.typography.bodySmall)
    }
  }
  Spacer(Modifier.weight(1f))
  Button(
      onClick = viewModel::createHousehold,
      enabled = state.name.isNotBlank() && !state.isLoading,
      modifier = Modifier.fillMaxWidth().testTag(CreateHouseholdTestTags.CREATE),
  ) {
    if (state.isLoading) {
      CircularProgressIndicator(
          modifier = Modifier.size(Dimens.progressSize).testTag(CreateHouseholdTestTags.LOADING),
          color = MaterialTheme.colorScheme.onPrimary,
          strokeWidth = Dimens.progressStrokeWidth,
      )
    } else {
      Text(stringResource(R.string.create_household))
    }
  }
}

@Composable
private fun FridgeTypeOption(
    type: FridgeType,
    state: CreateHouseholdUiState,
    onSelect: (FridgeType) -> Unit,
) {
  val isPrivate = type == FridgeType.PRIVATE
  val selected = state.type == type
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .testTag(
                  if (isPrivate) CreateHouseholdTestTags.TYPE_PRIVATE
                  else CreateHouseholdTestTags.TYPE_SHARED)
              .selectable(
                  selected = selected,
                  enabled = !state.isLoading && !state.hasCreationOperation,
                  role = Role.RadioButton,
                  onClick = { onSelect(type) },
              )
              .padding(vertical = Dimens.spacingXs),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(
        selected = selected,
        onClick = null,
        enabled = !state.isLoading && !state.hasCreationOperation)
    Spacer(Modifier.width(Dimens.spacingS))
    Column {
      Text(
          stringResource(
              if (isPrivate) R.string.fridge_type_private else R.string.fridge_type_shared))
      Text(
          stringResource(
              if (isPrivate) R.string.fridge_type_private_description
              else R.string.fridge_type_shared_description),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun ColumnScope.HouseholdReady(
    inviteCode: String,
    type: FridgeType,
    onContinue: () -> Unit,
) {
  val isPrivate = type == FridgeType.PRIVATE
  val colors = MaterialTheme.colorScheme
  val context = LocalContext.current
  Spacer(Modifier.height(Dimens.spacingXxl))
  Box(
      modifier =
          Modifier.align(Alignment.CenterHorizontally)
              .size(HouseholdLayout.readyBadgeSize)
              .shadow(HouseholdLayout.readyBadgeElevation, CircleShape)
              .background(colors.primary, CircleShape),
      contentAlignment = Alignment.Center,
  ) {
    Icon(
        imageVector = Icons.Filled.Check,
        contentDescription = null,
        tint = colors.onPrimary,
        modifier = Modifier.size(HouseholdLayout.readyBadgeIconSize),
    )
  }
  Spacer(Modifier.height(Dimens.spacingL))
  Text(
      text =
          stringResource(
              if (isPrivate) R.string.private_fridge_ready_title
              else R.string.household_ready_title),
      style = MaterialTheme.typography.headlineMedium,
      fontWeight = FontWeight.Bold,
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
      color = colors.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.screenHorizontalPadding),
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
  Spacer(Modifier.weight(1f))
  if (!isPrivate) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(HouseholdLayout.cardCornerRadius),
        color = colors.surfaceContainerLowest,
        border = BorderStroke(Dimens.borderWidth, colors.primaryContainer),
        shadowElevation = Dimens.cardElevation,
    ) {
      Column(
          modifier =
              Modifier.padding(
                  horizontal = Dimens.spacingL,
                  vertical = HouseholdLayout.readyCardVerticalPadding),
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
            text = stringResource(R.string.household_invite_code),
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(Dimens.spacingXs))
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
              text = inviteCode,
              style = MaterialTheme.typography.headlineLarge,
              fontWeight = FontWeight.Bold,
              color = colors.onPrimaryContainer,
          )
          Spacer(Modifier.width(Dimens.spacingXs))
          CopyInviteCodeButton(
              inviteCode = inviteCode,
              modifier = Modifier.testTag(CreateHouseholdTestTags.COPY_INVITE_CODE),
          )
        }
        TextButton(
            onClick = {
              val send =
                  Intent(Intent.ACTION_SEND)
                      .setType("text/plain")
                      .putExtra(Intent.EXTRA_TEXT, inviteCode)
              context.startActivity(Intent.createChooser(send, null))
            },
            modifier = Modifier.testTag(CreateHouseholdTestTags.SHARE_INVITE_CODE),
        ) {
          Text(stringResource(R.string.share_invite_code))
        }
      }
    }
  }
  Spacer(Modifier.weight(1f))
  Button(
      onClick = onContinue,
      modifier = Modifier.fillMaxWidth().testTag(CreateHouseholdTestTags.CONTINUE),
  ) {
    Text(stringResource(R.string.go_to_my_fridge))
  }
}
