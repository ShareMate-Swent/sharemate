// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.android.sharemate.R
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemImage
import com.android.sharemate.model.item.ItemImageOrigin
import com.android.sharemate.resources.C

private val ThumbnailSize = 80.dp

@Composable
internal fun FridgeItemImage(item: Item, imageLoader: ImageLoader) {
  val source = item.image?.reference?.takeIf { it.isNotBlank() }
  val context = LocalContext.current
  val pixels = with(LocalDensity.current) { ThumbnailSize.roundToPx() }
  // Recreate the painter so a reused card cannot display its previous image while loading.
  key(item.id, item.image?.origin, source) {
    Box(Modifier.size(ThumbnailSize), contentAlignment = Alignment.Center) {
      if (source == null) {
        FoodPlaceholder()
      } else {
        val request =
            remember(context, source, pixels) {
              ImageRequest.Builder(context)
                  .data(source)
                  .size(pixels, pixels)
                  .crossfade(false)
                  .build()
            }
        val painter =
            rememberAsyncImagePainter(
                model = request, imageLoader = imageLoader, contentScale = ContentScale.Crop)
        when (painter.state) {
          is AsyncImagePainter.State.Success ->
              Image(
                  painter = painter,
                  contentDescription = stringResource(R.string.fridge_image_description, item.name),
                  contentScale = ContentScale.Crop,
                  modifier = Modifier.size(ThumbnailSize).testTag(C.Tag.fridge_image))
          is AsyncImagePainter.State.Error -> FoodPlaceholder()
          is AsyncImagePainter.State.Loading,
          AsyncImagePainter.State.Empty ->
              CircularProgressIndicator(
                  modifier = Modifier.size(24.dp).testTag(C.Tag.fridge_image_loading))
        }
      }
    }
  }
}

@Composable
private fun FoodPlaceholder() {
  Image(
      painter = painterResource(R.drawable.ic_food_fallback),
      contentDescription = stringResource(R.string.fridge_image_placeholder),
      modifier = Modifier.size(ThumbnailSize).testTag(C.Tag.fridge_image_placeholder),
      contentScale = ContentScale.Fit)
}

@Composable
internal fun FridgeImageCredits(image: ItemImage?) {
  if (image == null || image.origin != ItemImageOrigin.REMOTE_IMAGE) return
  val license =
      when (image.license?.lowercase()) {
        "by" -> "CC BY"
        "cc0" -> "CC0"
        else -> image.license
      }
  val credits =
      listOfNotNull(
              image.title,
              image.author,
              image.sourceName,
              listOfNotNull(license, image.licenseVersion)
                  .filter { it.isNotBlank() }
                  .joinToString(" "))
          .filter { it.isNotBlank() }
          .joinToString(" · ")
  Column {
    if (credits.isNotEmpty()) Text(credits, style = MaterialTheme.typography.bodySmall)
    CreditLink(image.sourceUrl, stringResource(R.string.fridge_image_source))
    CreditLink(image.licenseUrl, stringResource(R.string.fridge_image_license))
    CreditLink(image.authorUrl, stringResource(R.string.fridge_image_author))
    Text(
        stringResource(R.string.fridge_image_openverse), style = MaterialTheme.typography.bodySmall)
  }
}

@Composable
private fun CreditLink(url: String?, label: String) {
  val uri = url?.let(Uri::parse) ?: return
  if (uri.scheme !in listOf("https", "http") || uri.host.isNullOrBlank()) return
  val handler = LocalUriHandler.current
  TextButton(
      onClick = {
        try {
          handler.openUri(url)
        } catch (_: IllegalArgumentException) {
          // Some devices do not have an application that can open web links.
        }
      }) {
        Text(label)
      }
}
