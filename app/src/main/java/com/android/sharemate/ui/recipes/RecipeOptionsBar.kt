// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.recipes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.android.sharemate.R
import com.android.sharemate.ui.theme.Dimens

/** Single selection driven entirely by RecipePresentationOption; tapping it again clears it. */
@Composable
internal fun RecipeOptionsBar(
    selected: RecipePresentationOption?,
    onSelect: (RecipePresentationOption?) -> Unit,
    modifier: Modifier = Modifier,
) {
  Column(modifier.fillMaxWidth().testTag(RecipeOptionsTestTags.BAR)) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
          Text(
              stringResource(R.string.recipes_sort_label),
              style = MaterialTheme.typography.titleSmall)
          TextButton(
              onClick = { onSelect(null) },
              enabled = selected != null,
              modifier = Modifier.testTag(RecipeOptionsTestTags.CLEAR)) {
                Text(stringResource(R.string.recipes_clear_options))
              }
        }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingS)) {
          RecipePresentationOption.entries.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option.takeUnless { it == selected }) },
                label = { Text(stringResource(option.label)) },
                modifier = Modifier.testTag(RecipeOptionsTestTags.option(option)))
          }
        }
  }
}

/** Stable tags for the options bar, its clear action and registry-defined choices. */
object RecipeOptionsTestTags {
  const val BAR = "recipe_options_bar"
  const val CLEAR = "recipe_options_clear"

  /** An option's explicit registry ID remains stable when its translated label changes. */
  fun option(option: RecipePresentationOption) = "recipe_option_${option.id}"
}
