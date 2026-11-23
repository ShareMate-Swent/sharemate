// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.Options
import com.android.sharemate.R
import com.android.sharemate.model.HttpHeaders
import com.android.sharemate.model.image.FoodImageHttp
import com.android.sharemate.model.image.FoodImageLicenseIds
import com.android.sharemate.model.image.FridgeImageReference
import com.android.sharemate.model.image.PersonalPhotoReference
import com.android.sharemate.model.image.PersonalPhotoRepository
import com.android.sharemate.model.image.RemoteFridgeImageReference
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemImage
import com.android.sharemate.model.item.ItemImageOrigin
import com.android.sharemate.resources.C
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer

private val ThumbnailSize = 80.dp

internal fun fridgeImageRequest(context: Context, source: String, pixels: Int): ImageRequest =
    thumbnailRequest(context, pixels).data(source).build()

private fun thumbnailRequest(context: Context, pixels: Int): ImageRequest.Builder =
    ImageRequest.Builder(context)
        .size(pixels, pixels)
        // Wikimedia rejects a generic library User-Agent for thumbnail downloads too.
        .setHeader(HttpHeaders.USER_AGENT, FoodImageHttp.USER_AGENT)
        .crossfade(false)

@Composable
internal fun FridgeItemImage(
    item: Item,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    personalPhotos: PersonalPhotoController? = null,
) {
  val source = item.image?.reference?.takeIf { it.isNotBlank() }
  val isStoragePhoto =
      item.image?.origin == ItemImageOrigin.PERSONAL_PHOTO &&
          source != null &&
          PersonalPhotoReference.hasStoragePrefix(source)
  val cache = personalPhotos?.imageCache
  val accessRevoked = cache?.isActive?.collectAsState()?.value == false
  val context = LocalContext.current
  BoxWithConstraints(modifier.size(ThumbnailSize), contentAlignment = Alignment.Center) {
    val pixels = constraints.maxWidth.coerceAtLeast(1)
    // Recreate the painter so a reused card cannot display its previous image while loading.
    key(item.id, item.image?.origin, source, personalPhotos) {
      val request =
          remember(context, source, pixels, personalPhotos, imageLoader) {
            when {
              source == null || isStoragePhoto && personalPhotos == null -> null
              isStoragePhoto ->
                  source
                      .takeIf { PersonalPhotoReference.hasItemPrefix(it, item.id) }
                      ?.let { runCatching { PersonalPhotoReference(it) }.getOrNull() }
                      ?.let {
                        cachedImageRequest(
                            context, it, pixels, checkNotNull(personalPhotos), imageLoader)
                      }
              personalPhotos != null && cache != null && source.toHttpUrlOrNull() != null ->
                  cachedImageRequest(
                      context,
                      RemoteFridgeImageReference(source),
                      pixels,
                      personalPhotos,
                      imageLoader)
              else -> fridgeImageRequest(context, source, pixels)
            }
          }
      if (request == null || accessRevoked) {
        FoodPlaceholder()
      } else {
        val painter =
            rememberAsyncImagePainter(
                model = request, imageLoader = imageLoader, contentScale = ContentScale.Crop)
        when (painter.state) {
          is AsyncImagePainter.State.Success ->
              Image(
                  painter = painter,
                  contentDescription = stringResource(R.string.fridge_image_description, item.name),
                  contentScale = ContentScale.Crop,
                  modifier = Modifier.fillMaxSize().testTag(C.Tag.fridge_image))
          is AsyncImagePainter.State.Error -> FoodPlaceholder()
          AsyncImagePainter.State.Empty,
          is AsyncImagePainter.State.Loading ->
              if (isStoragePhoto) FoodPlaceholder()
              else
                  CircularProgressIndicator(
                      modifier =
                          Modifier.size(FridgeLayout.imageProgressSize)
                              .testTag(C.Tag.fridge_image_loading))
        }
      }
    }
  }
}

private fun cachedImageRequest(
    context: Context,
    reference: FridgeImageReference,
    pixels: Int,
    photos: PersonalPhotoController,
    imageLoader: ImageLoader,
): ImageRequest {
  val cache = photos.imageCache
  val memoryKey = cache?.memoryKey(reference)?.let { MemoryCache.Key(it) }
  val removeIfRevoked = {
    // A decode can finish writing to memory while the session is being revoked.
    if (memoryKey != null && !cache.isActive.value) {
      imageLoader.memoryCache?.remove(memoryKey)
    }
  }
  return thumbnailRequest(context, pixels)
      .data(reference)
      .memoryCacheKey(memoryKey)
      .diskCachePolicy(CachePolicy.DISABLED)
      .fetcherFactory<FridgeImageReference> { data, options, loader ->
        Fetcher {
          try {
            val image =
                when (data) {
                  is PersonalPhotoReference -> photos.load(data)
                  is RemoteFridgeImageReference ->
                      checkNotNull(cache).load(data) { downloadRemoteImage(data, options, loader) }
                }
            SourceResult(
                source = ImageSource(Buffer().write(image.bytes), options.context),
                mimeType = null,
                dataSource = if (image.fromDisk) DataSource.DISK else DataSource.NETWORK)
          } catch (largeImage: OversizedRemoteImage) {
            largeImage.result
          }
        }
      }
      .listener(onCancel = { removeIfRevoked() }, onSuccess = { _, _ -> removeIfRevoked() })
      .build()
}

private suspend fun downloadRemoteImage(
    reference: RemoteFridgeImageReference,
    options: Options,
    loader: ImageLoader,
): ByteArray {
  val fetcher = loader.components.newFetcher(Uri.parse(reference.url), options, loader)?.first
  val result =
      fetcher?.fetch() as? SourceResult ?: throw IOException("Remote image could not be fetched")
  var keepSourceOpen = false
  try {
    val source = result.source.source()
    // Preserve rendering of older URLs that point to a full-size image. The buffered data is
    // still at the start of the stream when Coil takes ownership of an oversized response.
    if (source.request(PersonalPhotoRepository.MAX_BYTES + 1L)) {
      keepSourceOpen = true
      throw OversizedRemoteImage(result)
    }
    return source.readByteArray().also {
      if (it.isEmpty()) throw IOException("Remote image is empty")
    }
  } finally {
    if (!keepSourceOpen) result.source.close()
  }
}

private class OversizedRemoteImage(val result: SourceResult) : IOException()

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
        FoodImageLicenseIds.BY -> stringResource(R.string.fridge_image_license_cc_by)
        FoodImageLicenseIds.BY_SA -> stringResource(R.string.fridge_image_license_cc_by_sa)
        FoodImageLicenseIds.CC0 -> stringResource(R.string.fridge_image_license_cc0)
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
