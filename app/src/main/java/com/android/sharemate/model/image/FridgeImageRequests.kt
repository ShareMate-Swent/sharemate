// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import android.content.Context
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.ImageRequest
import com.android.sharemate.model.HttpHeaders
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemImageOrigin
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer

internal fun fridgeThumbnailRequest(context: Context, pixels: Int): ImageRequest.Builder =
    ImageRequest.Builder(context)
        .size(pixels, pixels)
        .setHeader(HttpHeaders.USER_AGENT, FoodImageHttp.USER_AGENT)
        .crossfade(false)

internal fun fridgeImageRequest(
    context: Context,
    item: Item,
    pixels: Int,
    session: FridgeImageCacheSession?,
    downloadPhoto: (suspend (PersonalPhotoReference) -> ByteArray)?,
): ImageRequest? {
  val image = item.image ?: return null
  val source = image.reference.takeIf { it.isNotBlank() } ?: return null
  if (image.origin == ItemImageOrigin.PERSONAL_PHOTO &&
      PersonalPhotoReference.hasStoragePrefix(source)) {
    if (downloadPhoto == null || !PersonalPhotoReference.hasItemPrefix(source, item.id)) return null
    val reference = runCatching { PersonalPhotoReference(source) }.getOrNull() ?: return null
    return session?.request(context, reference, downloadPhoto)
        ?: fridgeThumbnailRequest(context, pixels).firebasePhoto(reference, downloadPhoto).build()
  }
  if (session != null && downloadPhoto != null && source.toHttpUrlOrNull() != null) {
    return session.request(context, RemoteFridgeImageReference(source), downloadPhoto)
  }
  return fridgeThumbnailRequest(context, pixels).data(source).build()
}

internal fun ImageRequest.Builder.firebasePhoto(
    reference: PersonalPhotoReference,
    download: suspend (PersonalPhotoReference) -> ByteArray,
): ImageRequest.Builder =
    data(reference).fetcherFactory<PersonalPhotoReference> { data, options, _ ->
      Fetcher {
        SourceResult(
            ImageSource(Buffer().write(download(data)), options.context), null, DataSource.NETWORK)
      }
    }
