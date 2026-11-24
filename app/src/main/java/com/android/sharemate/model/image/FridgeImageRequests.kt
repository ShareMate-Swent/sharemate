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
import com.android.sharemate.model.item.isStoredPersonalPhoto
import java.io.File
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
  val builder = fridgeThumbnailRequest(context, pixels)
  if (image.isStoredPersonalPhoto()) {
    if (downloadPhoto == null || !PersonalPhotoReference.hasItemPrefix(source, item.id)) return null
    val reference = runCatching { PersonalPhotoReference(source) }.getOrNull() ?: return null
    builder.firebasePhoto(reference, downloadPhoto)
  } else {
    // Pass paths as files so a Windows drive letter is not interpreted as a URI scheme.
    builder.data(File(source).takeIf { it.isAbsolute } ?: source)
  }
  val request = builder.build()
  return session?.withCache(request) ?: request
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
