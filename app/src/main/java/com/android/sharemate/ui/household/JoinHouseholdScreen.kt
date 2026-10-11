// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.android.sharemate.R

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
}

/** Screen for joining a household with its invite code. */
@Composable
fun JoinHouseholdScreen(
    viewModel: JoinHouseholdViewModel,
    onJoined: () -> Unit,
    onBack: () -> Unit,
) {
  val state = viewModel.state.collectAsState().value
  val inviteCodeDescription = stringResource(R.string.household_invite_code)
  LaunchedEffect(state.joined) { if (state.joined) onJoined() }
  val colors = MaterialTheme.colorScheme
  Column(
      modifier =
          Modifier.fillMaxSize()
              .background(colors.surfaceContainerLow)
              .padding(horizontal = 24.dp, vertical = 16.dp),
  ) {
    HouseholdTopBar(
        title = stringResource(R.string.join_household_title),
        onBack = onBack,
        backTestTag = JoinHouseholdTestTags.BACK,
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.join_household_description),
        style = MaterialTheme.typography.bodyLarge,
        color = colors.onSurfaceVariant,
    )
    Spacer(Modifier.weight(1f))
    BasicTextField(
        value = state.inviteCode,
        onValueChange = viewModel::updateInviteCode,
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
              horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
          ) {
            repeat(JoinHouseholdViewModel.MAX_CODE_LENGTH) { index ->
              val isNext = index == state.inviteCode.length
              Box(
                  modifier =
                      Modifier.weight(1f, fill = false)
                          .size(width = 48.dp, height = 58.dp)
                          .testTag(
                              when (index) {
                                0 -> JoinHouseholdTestTags.CODE_BOX_1
                                1 -> JoinHouseholdTestTags.CODE_BOX_2
                                2 -> JoinHouseholdTestTags.CODE_BOX_3
                                3 -> JoinHouseholdTestTags.CODE_BOX_4
                                4 -> JoinHouseholdTestTags.CODE_BOX_5
                                else -> JoinHouseholdTestTags.CODE_BOX_6
                              })
                          .background(colors.surfaceContainerLowest, RoundedCornerShape(12.dp))
                          .border(
                              width = if (isNext) 2.dp else 1.dp,
                              color = if (isNext) colors.primary else colors.outlineVariant,
                              shape = RoundedCornerShape(12.dp)),
                  contentAlignment = Alignment.Center,
              ) {
                Text(
                    text = state.inviteCode.getOrNull(index)?.toString() ?: "",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
              }
            }
          }
        },
    )
    state.error?.let { error ->
      Spacer(Modifier.height(12.dp))
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
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
                      JoinHouseholdError.INVALID_CODE -> R.string.invalid_invite_code
                      else -> R.string.household_generic_error
                    }),
            style = MaterialTheme.typography.labelLarge,
            color = colors.error,
            modifier = Modifier.testTag(JoinHouseholdTestTags.ERROR),
        )
      }
    }
    Spacer(Modifier.weight(1f))
    Button(
        onClick = viewModel::joinHousehold,
        enabled =
            state.inviteCode.length == JoinHouseholdViewModel.MAX_CODE_LENGTH && !state.isLoading,
        modifier = Modifier.fillMaxWidth().testTag(JoinHouseholdTestTags.JOIN),
    ) {
      if (state.isLoading) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp).testTag(JoinHouseholdTestTags.LOADING),
            color = colors.onPrimary,
            strokeWidth = 2.dp,
        )
      } else {
        Text(stringResource(R.string.join_household))
      }
    }
  }
}
