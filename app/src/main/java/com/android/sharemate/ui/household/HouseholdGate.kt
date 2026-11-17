// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.household

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.sharemate.R
import com.android.sharemate.model.household.Household
import com.android.sharemate.ui.theme.Dimens

object HouseholdGateTestTags {
  const val LOADING = "household_loading"
  const val RETRY = "household_retry"
  const val SIGN_OUT = "household_sign_out"
}

/** Displays household onboarding or application content according to membership state. */
@Composable
fun HouseholdGate(
    viewModel: HouseholdGateViewModel,
    onCreateHousehold: () -> Unit,
    onJoinHousehold: () -> Unit,
    content: @Composable (Household) -> Unit,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()

  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    when (val current = state) {
      HouseholdGateState.Loading ->
          CircularProgressIndicator(modifier = Modifier.testTag(HouseholdGateTestTags.LOADING))
      HouseholdGateState.NeedsHousehold ->
          HouseholdChoiceScreen(
              onCreateHousehold = onCreateHousehold,
              onJoinHousehold = onJoinHousehold,
          )
      is HouseholdGateState.Ready -> content(current.household)
      HouseholdGateState.Error ->
          Column(
              modifier = Modifier.padding(Dimens.spacingL),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(Dimens.spacingM),
          ) {
            Text(
                text = stringResource(R.string.household_generic_error),
                color = MaterialTheme.colorScheme.error,
            )
            Button(
                onClick = viewModel::refresh,
                modifier = Modifier.testTag(HouseholdGateTestTags.RETRY),
            ) {
              Text(stringResource(R.string.household_retry))
            }
          }
    }
  }
}
