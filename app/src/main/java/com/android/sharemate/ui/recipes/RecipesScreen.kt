// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.recipes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.sharemate.R
import com.android.sharemate.ui.theme.Dimens

/** Rendering and retries only: composing or revisiting this screen never starts a request. */
@Composable
fun RecipesScreen(
    viewModel: RecipesViewModel,
    modifier: Modifier = Modifier,
    isDemo: Boolean = false,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val selectedOption by viewModel.selectedOption.collectAsStateWithLifecycle()
  Column(modifier.fillMaxSize()) {
    if (isDemo) {
      Text(
          stringResource(R.string.recipes_demo_notice),
          style = MaterialTheme.typography.bodySmall,
          modifier =
              Modifier.padding(
                      horizontal = Dimens.screenHorizontalPadding, vertical = Dimens.spacingXs)
                  .testTag(RecipesTestTags.DEMO_NOTICE))
    }
    Box(Modifier.weight(1f)) {
      RecipesContent(
          state = state,
          onRetry = viewModel::requestRecipes,
          selectedOption = selectedOption,
          onOptionSelected = viewModel::selectOption)
    }
  }
}
