package com.android.sharemate.ui.fridge

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.android.sharemate.model.image.FoodImageHttp
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemImage
import com.android.sharemate.model.item.ItemImageOrigin
import com.android.sharemate.resources.C
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ThumbnailSize = 80.dp

internal fun fridgeImageRequest(context: Context, source: String, pixels: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(source)
        .size(pixels, pixels)
        // Wikimedia rejects a generic library User-Agent for thumbnail downloads too.
        .setHeader("User-Agent", FoodImageHttp.USER_AGENT)
        .crossfade(false)
        .build()

@Composable
internal fun FridgeItemImage(
    item: Item,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier.size(ThumbnailSize),
    personalPhotos: PersonalPhotoController? = null,
) {
    val source = item.image?.reference?.takeIf { it.isNotBlank() }
    if (item.image?.origin == ItemImageOrigin.PERSONAL_PHOTO &&
        source?.startsWith("itemPhotos/") == true) {
        // Scope decoded private pixels to this item and fridge session.
        key(item.id, source, personalPhotos) {
            BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
                val pixels = this.constraints.maxWidth
                StoredPersonalPhoto(item.name, source, pixels, personalPhotos)
            }
        }
        return
    }

    val context = LocalContext.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val pixels = this.constraints.maxWidth
        // Recreate the painter so a reused card cannot display its previous image while loading.
        key(item.id, item.image?.origin, source) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (source == null) {
                    FoodPlaceholder()
                } else {
                    val request =
                        remember(context, source, pixels) { fridgeImageRequest(context, source, pixels) }
                    val painter =
                        rememberAsyncImagePainter(
                            model = request, imageLoader = imageLoader, contentScale = ContentScale.Crop)
                    when (painter.state) {
                        is AsyncImagePainter.State.Success ->
                            Image(
                                painter = painter,
                                contentDescription =
                                    stringResource(R.string.fridge_image_description, item.name),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().testTag(C.Tag.fridge_image))
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
}

@Composable
private fun StoredPersonalPhoto(
    name: String,
    source: String,
    pixels: Int,
    photos: PersonalPhotoController?,
) {
  val bitmap by
      produceState<ImageBitmap?>(null, source, pixels, photos) {
        value = null
        value =
            try {
              val bytes = photos?.load(source) ?: return@produceState
              withContext(Dispatchers.Default) { decodePersonalThumbnail(bytes, pixels) }
            } catch (cancelled: CancellationException) {
              throw cancelled
            } catch (_: Exception) {
              null
            }
      }
  val image = bitmap
  if (image == null) FoodPlaceholder()
  else
      Image(
          bitmap = image,
          contentDescription = stringResource(R.string.fridge_image_description, name),
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize().testTag(C.Tag.fridge_image))
}

internal fun decodePersonalThumbnail(bytes: ByteArray, pixels: Int): ImageBitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _
          ->
          decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
          val width = info.size.width
          val height = info.size.height
          val longest = maxOf(width, height)
          val target = pixels.coerceIn(1, longest)
          if (target < longest)
              decoder.setTargetSize(
                  (width.toLong() * target / longest).toInt().coerceAtLeast(1),
                  (height.toLong() * target / longest).toInt().coerceAtLeast(1))
        }
        .asImageBitmap()

@Composable
private fun FoodPlaceholder() {
  Image(
      painter = painterResource(R.drawable.ic_food_fallback),
      contentDescription = stringResource(R.string.fridge_image_placeholder),
      modifier = Modifier.fillMaxSize().testTag(C.Tag.fridge_image_placeholder),
      contentScale = ContentScale.Fit)
}

@Composable
internal fun FridgeImageCredits(image: ItemImage?) {
  if (image == null || image.origin != ItemImageOrigin.REMOTE_IMAGE) return
  val license =
      when (image.license?.lowercase()) {
        "by" -> "CC BY"
        "by-sa" -> "CC BY-SA"
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
