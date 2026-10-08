// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.household

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinHouseholdScreen(
    viewModel: JoinHouseholdViewModel,
    onJoined: () -> Unit,
    onBack: () -> Unit,
) {
  val state = viewModel.state.collectAsState().value
  val inviteCodeDescription = stringResource(R.string.household_invite_code)
  LaunchedEffect(state.joined) { if (state.joined) onJoined() }
  Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
    TopAppBar(
        title = { Text(stringResource(R.string.join_household_title)) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag(JoinHouseholdTestTags.BACK)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
            )
          }
        },
    )
    Text(stringResource(R.string.join_household_description))
    BasicTextField(
        value = state.inviteCode,
        onValueChange = viewModel::updateInviteCode,
        modifier =
            Modifier.fillMaxWidth().testTag(JoinHouseholdTestTags.CODE_INPUT).semantics {
              contentDescription = inviteCodeDescription
            },
        enabled = !state.isLoading,
        singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
            ),
        decorationBox = {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(JoinHouseholdViewModel.MAX_CODE_LENGTH) { index ->
              Text(
                  text = state.inviteCode.getOrNull(index)?.toString() ?: "",
                  style = MaterialTheme.typography.headlineMedium,
                  modifier =
                      Modifier.weight(1f)
                          .testTag(
                              when (index) {
                                0 -> JoinHouseholdTestTags.CODE_BOX_1
                                1 -> JoinHouseholdTestTags.CODE_BOX_2
                                2 -> JoinHouseholdTestTags.CODE_BOX_3
                                3 -> JoinHouseholdTestTags.CODE_BOX_4
                                4 -> JoinHouseholdTestTags.CODE_BOX_5
                                else -> JoinHouseholdTestTags.CODE_BOX_6
                              }))
            }
          }
        },
    )
    state.error?.let { error ->
      Text(
          text =
              stringResource(
                  when (error) {
                    JoinHouseholdError.INVALID_CODE -> R.string.invalid_invite_code
                    else -> R.string.household_generic_error
                  }),
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag(JoinHouseholdTestTags.ERROR),
      )
    }
    Button(
        onClick = viewModel::joinHousehold,
        enabled =
            state.inviteCode.length == JoinHouseholdViewModel.MAX_CODE_LENGTH && !state.isLoading,
        modifier = Modifier.fillMaxWidth().testTag(JoinHouseholdTestTags.JOIN),
    ) {
      if (state.isLoading) {
        CircularProgressIndicator(
            modifier = Modifier.testTag(JoinHouseholdTestTags.LOADING),
            color = MaterialTheme.colorScheme.onPrimary,
        )
      } else {
        Text(stringResource(R.string.join_household))
      }
    }
  }
}
