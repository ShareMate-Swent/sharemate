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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.sharemate.R
import com.android.sharemate.model.household.Household

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
          CircularProgressIndicator(modifier = Modifier.testTag("household_loading"))
      HouseholdGateState.NeedsHousehold ->
          HouseholdChoiceScreen(
              onCreateHousehold = onCreateHousehold,
              onJoinHousehold = onJoinHousehold,
          )
      is HouseholdGateState.Ready -> content(current.household)
      HouseholdGateState.Error ->
          Column(
              modifier = Modifier.padding(24.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            Text(
                text = stringResource(R.string.household_generic_error),
                color = MaterialTheme.colorScheme.error,
            )
            Button(
                onClick = viewModel::refresh,
                modifier = Modifier.testTag("household_retry"),
            ) {
              Text(stringResource(R.string.household_retry))
            }
          }
    }
  }
}
