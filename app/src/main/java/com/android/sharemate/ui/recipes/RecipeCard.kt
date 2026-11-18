// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.recipes

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
                  maxLines = 3,
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
          Image(
              painterResource(R.drawable.ic_food_fallback),
              contentDescription = stringResource(R.string.recipes_image_unavailable, data.title),
              contentScale = ContentScale.Fit,
              modifier =
                  Modifier.fillMaxSize()
                      .padding(Dimens.spacingM)
                      .testTag(RecipesTestTags.placeholder(data.id)))
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
