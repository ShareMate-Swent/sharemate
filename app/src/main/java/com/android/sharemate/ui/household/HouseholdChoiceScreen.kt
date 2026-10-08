// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.household

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.sharemate.R

/** Test tags used by [HouseholdChoiceScreen]. */
object HouseholdChoiceTestTags {
  const val CREATE = "household_choice_create"
  const val JOIN = "household_choice_join"
}

/** Lets a user choose whether to create or join a household. */
@Composable
fun HouseholdChoiceScreen(
    onCreateHousehold: () -> Unit,
    onJoinHousehold: () -> Unit,
) {
  Column(
      modifier = Modifier.padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(
        text = stringResource(R.string.household_choice_title),
        style = MaterialTheme.typography.headlineMedium,
    )
    Button(
        onClick = onCreateHousehold,
        modifier = Modifier.fillMaxWidth().testTag(HouseholdChoiceTestTags.CREATE),
    ) {
      Text(stringResource(R.string.create_a_household))
    }
    Button(
        onClick = onJoinHousehold,
        modifier = Modifier.fillMaxWidth().testTag(HouseholdChoiceTestTags.JOIN),
    ) {
      Text(stringResource(R.string.join_a_household))
    }
  }
}
