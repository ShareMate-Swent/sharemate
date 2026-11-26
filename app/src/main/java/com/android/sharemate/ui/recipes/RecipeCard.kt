// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.recipes

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.android.sharemate.R
import com.android.sharemate.ui.theme.Dimens

private const val IMAGE_ASPECT_RATIO = 16f / 9f
private const val IMAGE_MAX_PIXELS = 1280
private const val TITLE_MAX_LINES = 3

/** A non-clickable recipe summary, ready for a future caller to supply real recipe data. */
@Composable
fun RecipeCard(
    data: RecipeCardData,
    modifier: Modifier = Modifier,
    imageLoader: ImageLoader = LocalContext.current.imageLoader,
) {
  Card(
      modifier = modifier.fillMaxWidth().testTag(RecipesTestTags.card(data.id)),
      shape = MaterialTheme.shapes.medium,
      elevation = CardDefaults.cardElevation(defaultElevation = Dimens.cardElevation)) {
        RecipeImage(data, imageLoader)
        Column(
            Modifier.padding(Dimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
              Text(
                  data.title,
                  style = MaterialTheme.typography.titleMedium,
                  maxLines = TITLE_MAX_LINES,
                  overflow = TextOverflow.Ellipsis,
                  modifier =
                      Modifier.testTag(RecipesTestTags.title(data.id)).semantics { heading() })
              Text(
                  pluralStringResource(
                      R.plurals.recipes_matching_ingredients,
                      data.matchingIngredientCount,
                      data.matchingIngredientCount),
                  style = MaterialTheme.typography.bodyMedium,
                  modifier = Modifier.testTag(RecipesTestTags.matching(data.id)))
              Text(
                  pluralStringResource(
                      R.plurals.recipes_missing_ingredients,
                      data.missingIngredientCount,
                      data.missingIngredientCount),
                  style = MaterialTheme.typography.bodyMedium,
                  modifier = Modifier.testTag(RecipesTestTags.missing(data.id)))
              if (data.missingIngredients.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.recipes_missing_names, data.missingIngredients.joinToString(", ")),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(RecipesTestTags.missingNames(data.id)))
              }
            }
      }
}

@Composable
private fun RecipeImage(data: RecipeCardData, imageLoader: ImageLoader) {
  val context = LocalContext.current
  val url = data.imageUrl?.takeIf { it.isNotBlank() }
  var failed by remember(url, imageLoader) { mutableStateOf(false) }
  Box(
      Modifier.fillMaxWidth()
          .aspectRatio(IMAGE_ASPECT_RATIO)
          .background(MaterialTheme.colorScheme.surfaceVariant),
      contentAlignment = Alignment.Center) {
        if (url == null || failed) {
          Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painterResource(R.drawable.ic_food_fallback),
                contentDescription = stringResource(R.string.recipes_image_unavailable, data.title),
                contentScale = ContentScale.Fit,
                modifier =
                    Modifier.fillMaxWidth()
                        .weight(1f)
                        .padding(Dimens.spacingM)
                        .testTag(RecipesTestTags.placeholder(data.id)))
            if (failed) {
              val retryDescription =
                  stringResource(R.string.recipes_image_retry_description, data.title)
              TextButton(
                  onClick = { failed = false },
                  modifier =
                      Modifier.testTag(RecipesTestTags.imageRetry(data.id)).semantics {
                        contentDescription = retryDescription
                      }) {
                    Text(stringResource(R.string.recipes_image_retry))
                  }
            }
          }
        } else {
          AsyncImage(
              model = ImageRequest.Builder(context).data(url).size(IMAGE_MAX_PIXELS).build(),
              imageLoader = imageLoader,
              contentDescription = stringResource(R.string.recipes_image_description, data.title),
              contentScale = ContentScale.Crop,
              onError = { failed = true },
              modifier = Modifier.fillMaxSize().testTag(RecipesTestTags.image(data.id)))
        }
      }
}
