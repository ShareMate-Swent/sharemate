// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.household

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.android.sharemate.R

/** Test tags used by [CreateHouseholdScreen]. */
object CreateHouseholdTestTags {
  const val NAME = "create_household_name"
  const val CREATE = "create_household_button"
  const val BACK = "create_household_back"
  const val COPY_INVITE_CODE = "copy_invite_code"
  const val CONTINUE = "create_household_continue"
  const val LOADING = "create_household_loading"
  const val ERROR = "create_household_error"
}

/** Screen for creating a household and sharing its invite code. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateHouseholdScreen(
    viewModel: CreateHouseholdViewModel,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
  val state = viewModel.state.collectAsState().value
  val clipboard = LocalClipboardManager.current
  Column(
      modifier = Modifier.padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    TopAppBar(
        title = { Text(stringResource(R.string.create_household_title)) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag(CreateHouseholdTestTags.BACK)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
            )
          }
        },
    )
    if (state.inviteCode == null) {
      Text(stringResource(R.string.create_household_description))
      OutlinedTextField(
          value = state.name,
          onValueChange = viewModel::updateName,
          label = { Text(stringResource(R.string.household_name)) },
          placeholder = { Text(stringResource(R.string.household_name_placeholder)) },
          modifier = Modifier.fillMaxWidth().testTag(CreateHouseholdTestTags.NAME),
          enabled = !state.isLoading,
          singleLine = true,
      )
      Button(
          onClick = viewModel::createHousehold,
          enabled = state.name.isNotBlank() && !state.isLoading,
          modifier = Modifier.fillMaxWidth().testTag(CreateHouseholdTestTags.CREATE),
      ) {
        if (state.isLoading) {
          CircularProgressIndicator(
              modifier = Modifier.testTag(CreateHouseholdTestTags.LOADING),
              color = MaterialTheme.colorScheme.onPrimary,
          )
        } else {
          Text(stringResource(R.string.create_household))
        }
      }
      state.error?.let {
        Text(
            text = stringResource(R.string.household_generic_error),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(CreateHouseholdTestTags.ERROR),
        )
      }
    } else {
      Text(
          text = stringResource(R.string.household_ready_title),
          style = MaterialTheme.typography.headlineMedium,
      )
      Text(stringResource(R.string.household_ready_description))
      Text(stringResource(R.string.household_invite_code))
      Row {
        Text(
            text = state.inviteCode,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { clipboard.setText(AnnotatedString(state.inviteCode)) },
            modifier = Modifier.testTag(CreateHouseholdTestTags.COPY_INVITE_CODE),
        ) {
          Icon(
              imageVector = Icons.Default.ContentCopy,
              contentDescription = stringResource(R.string.copy_invite_code),
          )
        }
      }
      Button(
          onClick = onContinue,
          modifier = Modifier.fillMaxWidth().testTag(CreateHouseholdTestTags.CONTINUE),
      ) {
        Text(stringResource(R.string.go_to_my_fridge))
      }
    }
  }
}
