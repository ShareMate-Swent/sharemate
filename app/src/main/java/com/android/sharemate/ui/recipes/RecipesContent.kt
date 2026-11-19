// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import coil.ImageLoader
import coil.imageLoader
import com.android.sharemate.R
import com.android.sharemate.ui.theme.Dimens

/**
 * Stateless presentation: the caller owns request state and retry; no data source is created here.
 */
@Composable
fun RecipesContent(
    state: RecipesUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    imageLoader: ImageLoader = LocalContext.current.imageLoader,
) {
  val loadingDescription = stringResource(R.string.recipes_loading)
  Column(
      modifier
          .fillMaxSize()
          .padding(
              horizontal = Dimens.screenHorizontalPadding, vertical = Dimens.screenVerticalPadding),
      verticalArrangement = Arrangement.spacedBy(Dimens.spacingM)) {
        Text(
            stringResource(R.string.recipes_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() })
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
          when (state) {
            RecipesUiState.Loading ->
                CircularProgressIndicator(
                    modifier =
                        Modifier.testTag(RecipesTestTags.LOADING).semantics {
                          contentDescription = loadingDescription
                        })
            is RecipesUiState.Error ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Dimens.spacingS)) {
                      Text(
                          stringResource(
                              when (state.reason) {
                                RecipesErrorReason.OFFLINE -> R.string.recipes_offline
                                RecipesErrorReason.SERVICE -> R.string.recipes_error
                              }),
                          textAlign = TextAlign.Center,
                          color = MaterialTheme.colorScheme.error,
                          modifier =
                              Modifier.testTag(RecipesTestTags.ERROR).semantics {
                                liveRegion = LiveRegionMode.Polite
                              })
                      TextButton(
                          onClick = onRetry, modifier = Modifier.testTag(RecipesTestTags.RETRY)) {
                            Text(stringResource(R.string.recipes_retry))
                          }
                    }
            is RecipesUiState.Success ->
                if (state.recipes.isEmpty()) {
                  Text(
                      stringResource(R.string.recipes_empty),
                      textAlign = TextAlign.Center,
                      modifier = Modifier.testTag(RecipesTestTags.EMPTY))
                } else {
                  LazyColumn(
                      modifier = Modifier.fillMaxSize().testTag(RecipesTestTags.LIST),
                      verticalArrangement = Arrangement.spacedBy(Dimens.spacingM)) {
                        items(state.recipes, key = { it.id }) { recipe ->
                          RecipeCard(recipe, imageLoader = imageLoader)
                        }
                      }
                }
          }
        }
      }
}

/** Stable status tags and ID-based tags for cards and their individual accessible elements. */
object RecipesTestTags {
  const val LOADING = "recipes_loading"
  const val EMPTY = "recipes_empty"
  const val ERROR = "recipes_error"
  const val RETRY = "recipes_retry"
  const val LIST = "recipes_list"

  fun card(id: String) = "recipe_card_$id"

  fun title(id: String) = "recipe_title_$id"

  fun matching(id: String) = "recipe_matching_$id"

  fun missing(id: String) = "recipe_missing_$id"

  fun image(id: String) = "recipe_image_$id"

  fun placeholder(id: String) = "recipe_placeholder_$id"

  fun imageRetry(id: String) = "recipe_image_retry_$id"
}
