// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.household

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.sharemate.R
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.text.TextLimits
import com.android.sharemate.ui.TextLengthCounter
import com.android.sharemate.ui.components.AppBackButton
import com.android.sharemate.ui.components.AppPrimaryButton
import com.android.sharemate.ui.components.AppScreenTitle
import com.android.sharemate.ui.components.AppStepIndicator
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
  val state = viewModel.state.collectAsStateWithLifecycle().value
  Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(
                    horizontal = Dimens.screenHorizontalPadding,
                    vertical = Dimens.screenVerticalPadding),
    ) {
      ScrollingColumn {
        if (state.inviteCode == null) {
          CreateHouseholdForm(state, viewModel, onBack)
        } else {
          HouseholdReady(state.name, state.inviteCode, state.type, onContinue)
        }
      }
    }
  }
}

/**
 * A column that fills the screen and scrolls when its content is taller, for example on a small
 * phone or with a large system font, so the last action can always be reached.
 */
@Composable
internal fun ScrollingColumn(content: @Composable ColumnScope.() -> Unit) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    Column(
        modifier =
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
        content = content,
    )
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
  val editable = !state.isLoading && !state.hasCreationOperation
  AppStepIndicator(HouseholdLayout.FORM_STEP, HouseholdLayout.ONBOARDING_STEP_COUNT)
  Spacer(Modifier.height(Dimens.spacingL))
  AppBackButton(onBack = onBack, testTag = CreateHouseholdTestTags.BACK)
  Spacer(Modifier.height(Dimens.spacingL))
  AppScreenTitle(
      title = stringResource(R.string.create_household_title),
      description = stringResource(R.string.create_household_description),
  )
  Spacer(Modifier.height(Dimens.spacingL))
  HouseholdNameField(state, editable, viewModel::updateName)
  Spacer(Modifier.height(Dimens.spacingM))
  Text(stringResource(R.string.fridge_type), style = MaterialTheme.typography.labelMedium)
  Spacer(Modifier.height(Dimens.spacingXs))
  Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Dimens.spacingS)) {
    FridgeTypeOption(FridgeType.SHARED, state.type, editable, viewModel::updateType)
    FridgeTypeOption(FridgeType.PRIVATE, state.type, editable, viewModel::updateType)
  }
  state.error?.let { CreateErrorBanner(state.hasCreationOperation) }
  Spacer(Modifier.weight(1f))
  AppPrimaryButton(
      text = stringResource(R.string.create_household),
      onClick = viewModel::createHousehold,
      enabled = state.name.isNotBlank(),
      loading = state.isLoading,
      loadingTestTag = CreateHouseholdTestTags.LOADING,
      modifier = Modifier.testTag(CreateHouseholdTestTags.CREATE),
  )
}

/** The household name input, with its label above the field and a length counter below. */
@Composable
private fun HouseholdNameField(
    state: CreateHouseholdUiState,
    enabled: Boolean,
    onNameChange: (String) -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  Text(stringResource(R.string.household_name), style = MaterialTheme.typography.labelMedium)
  Spacer(Modifier.height(Dimens.spacingXs))
  OutlinedTextField(
      value = state.name,
      onValueChange = onNameChange,
      placeholder = { Text(stringResource(R.string.household_name_placeholder)) },
      supportingText = { TextLengthCounter(state.name, TextLimits.HOUSEHOLD_NAME) },
      modifier = Modifier.fillMaxWidth().testTag(CreateHouseholdTestTags.NAME),
      enabled = enabled,
      singleLine = true,
      shape = MaterialTheme.shapes.medium,
      colors =
          OutlinedTextFieldDefaults.colors(
              focusedContainerColor = colors.surface,
              unfocusedContainerColor = colors.surface,
              focusedBorderColor = colors.primary,
              unfocusedBorderColor = colors.outlineVariant,
          ),
  )
}

/** The creation error banner, with the hint to go back when a creation request is pending. */
@Composable
private fun CreateErrorBanner(hasCreationOperation: Boolean) {
  Spacer(Modifier.height(Dimens.spacingM))
  HouseholdErrorBanner(
      message = stringResource(R.string.household_generic_error),
      testTag = CreateHouseholdTestTags.ERROR,
  )
  if (hasCreationOperation) {
    Spacer(Modifier.height(Dimens.spacingXs))
    Text(
        text = stringResource(R.string.create_household_change_request_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** A selectable card for one fridge type; the selected one is outlined in the primary color. */
@Composable
private fun FridgeTypeOption(
    type: FridgeType,
    selectedType: FridgeType,
    enabled: Boolean,
    onSelect: (FridgeType) -> Unit,
) {
  val isPrivate = type == FridgeType.PRIVATE
  val selected = selectedType == type
  val colors = MaterialTheme.colorScheme
  Surface(
      shape = MaterialTheme.shapes.medium,
      color = colors.surface,
      border = if (selected) BorderStroke(Dimens.focusedBorderWidth, colors.primary) else null,
      modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .testTag(
                    if (isPrivate) CreateHouseholdTestTags.TYPE_PRIVATE
                    else CreateHouseholdTestTags.TYPE_SHARED)
                .selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = { onSelect(type) },
                )
                .padding(Dimens.cardPadding),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      RadioButton(selected = selected, onClick = null, enabled = enabled)
      Column {
        Text(
            stringResource(
                if (isPrivate) R.string.fridge_type_private else R.string.fridge_type_shared),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(
                if (isPrivate) R.string.fridge_type_private_description
                else R.string.fridge_type_shared_description),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
      }
    }
  }
}
