// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.household

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.sharemate.R
import com.android.sharemate.ui.components.AppBackButton
import com.android.sharemate.ui.components.AppPrimaryButton
import com.android.sharemate.ui.components.AppScreenTitle
import com.android.sharemate.ui.theme.Dimens

/** Test tags used by [JoinHouseholdScreen]. */
object JoinHouseholdTestTags {
  const val BACK = "join_household_back"
  const val CODE_INPUT = "join_household_code"
  const val CODE_BOX_1 = "join_household_code_box_1"
  const val CODE_BOX_2 = "join_household_code_box_2"
  const val CODE_BOX_3 = "join_household_code_box_3"
  const val CODE_BOX_4 = "join_household_code_box_4"
  const val CODE_BOX_5 = "join_household_code_box_5"
  const val CODE_BOX_6 = "join_household_code_box_6"
  const val JOIN = "join_household_button"
  const val LOADING = "join_household_loading"
  const val ERROR = "join_household_error"

  /** The test tags of the six code boxes, in display order. */
  val CODE_BOXES = listOf(CODE_BOX_1, CODE_BOX_2, CODE_BOX_3, CODE_BOX_4, CODE_BOX_5, CODE_BOX_6)
}

/** Screen for joining a household with its invite code. */
@Composable
fun JoinHouseholdScreen(
    viewModel: JoinHouseholdViewModel,
    onJoined: () -> Unit,
    onBack: () -> Unit,
) {
  val state = viewModel.state.collectAsStateWithLifecycle().value
  LaunchedEffect(state.joined) { if (state.joined) onJoined() }
  Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(
                    horizontal = Dimens.screenHorizontalPadding,
                    vertical = Dimens.screenVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingL),
    ) {
      AppBackButton(onBack = onBack, testTag = JoinHouseholdTestTags.BACK)
      AppScreenTitle(
          title = stringResource(R.string.join_household_title),
          description = stringResource(R.string.join_household_description),
      )
      InviteCodeField(state, viewModel::updateInviteCode)
      state.error?.let { error ->
        HouseholdErrorBanner(
            message =
                stringResource(
                    when (error) {
                      JoinHouseholdError.INVALID_CODE -> R.string.invalid_invite_code
                      else -> R.string.household_generic_error
                    }),
            testTag = JoinHouseholdTestTags.ERROR,
        )
      }
      Spacer(Modifier.weight(1f))
      AppPrimaryButton(
          text = stringResource(R.string.join_household),
          onClick = viewModel::joinHousehold,
          enabled = state.inviteCode.length == JoinHouseholdViewModel.MAX_CODE_LENGTH,
          loading = state.isLoading,
          loadingTestTag = JoinHouseholdTestTags.LOADING,
          modifier = Modifier.testTag(JoinHouseholdTestTags.JOIN),
      )
    }
  }
}

/** The invisible text field that receives the typed code, drawn as one box per character. */
@Composable
private fun InviteCodeField(state: JoinHouseholdUiState, onCodeChange: (String) -> Unit) {
  val inviteCodeDescription = stringResource(R.string.household_invite_code)
  BasicTextField(
      value = state.inviteCode,
      onValueChange = onCodeChange,
      modifier =
          Modifier.fillMaxWidth().testTag(JoinHouseholdTestTags.CODE_INPUT).semantics {
            contentDescription = inviteCodeDescription
          },
      enabled = !state.isLoading,
      singleLine = true,
      cursorBrush = SolidColor(Color.Transparent),
      keyboardOptions =
          KeyboardOptions(
              capitalization = KeyboardCapitalization.Characters,
              keyboardType = KeyboardType.Ascii,
          ),
      decorationBox = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(Dimens.spacingXs, Alignment.CenterHorizontally),
        ) {
          repeat(JoinHouseholdViewModel.MAX_CODE_LENGTH) { index ->
            CodeCell(
                character = state.inviteCode.getOrNull(index)?.toString().orEmpty(),
                isNext = index == state.inviteCode.length,
                hasError = state.error != null,
                testTag = JoinHouseholdTestTags.CODE_BOXES[index],
                modifier = Modifier.weight(1f, fill = false),
            )
          }
        }
      },
  )
}

/** One invite code box: red after a failed join, highlighted where the next character goes. */
@Composable
private fun CodeCell(
    character: String,
    isNext: Boolean,
    hasError: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  val borderColor =
      when {
        hasError -> colors.error
        isNext -> colors.primary
        else -> colors.outlineVariant
      }
  Box(
      modifier =
          modifier
              .size(width = HouseholdLayout.codeCellWidth, height = HouseholdLayout.codeCellHeight)
              .testTag(testTag)
              .background(colors.surface, MaterialTheme.shapes.small)
              .border(
                  width = if (isNext || hasError) Dimens.focusedBorderWidth else Dimens.borderWidth,
                  color = borderColor,
                  shape = MaterialTheme.shapes.small),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        text = character,
        style = MaterialTheme.typography.titleLarge,
        color = colors.onSurface,
    )
  }
}
