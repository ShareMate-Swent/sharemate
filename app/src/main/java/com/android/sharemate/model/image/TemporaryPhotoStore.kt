// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal const val MAX_INPUT_PHOTO_BYTES = 20L * 1024 * 1024
internal const val MAX_INPUT_PHOTO_PIXELS = 50_000_000L

/** Owns temporary copies only. A confirmed URI remains readable until its form is dismissed. */
interface TemporaryPhotoStore {
  /**
   * Returns a private file URI for in-process CameraX output. Never send it to an external camera
   * intent; external sharing would require a FileProvider content URI instead.
   */
  suspend fun createCapture(): String

  suspend fun importImage(uri: String): String

  suspend fun validate(uri: String)

  suspend fun delete(uri: String)
}

class AndroidTemporaryPhotoStore(
    context: Context,
    private val personalGalleryOnly: Boolean = false,
) : TemporaryPhotoStore {
  private val resolver = context.applicationContext.contentResolver
  private val directory = temporaryPhotoDirectory(context)
  private val galleryMaxBytes =
      if (personalGalleryOnly) PersonalPhotoRepository.MAX_BYTES.toLong() else MAX_INPUT_PHOTO_BYTES

  override suspend fun createCapture(): String {
    var created: File? = null
    try {
      return withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs())
        val file = File.createTempFile("capture-", ".jpg", directory)
        created = file
        Uri.fromFile(file).toString()
      }
    } catch (failure: Throwable) {
      withContext(NonCancellable + Dispatchers.IO) { created?.delete() }
      throw failure
    }
  }

  override suspend fun importImage(uri: String): String {
    var imported: String? = null
    try {
      return withContext(Dispatchers.IO) {
        require(Uri.parse(uri).scheme == "content")
        val target = createCapture()
        imported = target
        resolver.openInputStream(Uri.parse(uri))?.use { input ->
          ownedFile(target).outputStream().use { output ->
            input.copyPhotoTo(output, galleryMaxBytes, checkActive = { ensureActive() }) {
              IOException("Image exceeds size limit")
            }
          }
        } ?: throw IOException("Image is unavailable")
        validate(target, requirePersonalFormat = personalGalleryOnly)
        target
      }
    } catch (failure: Throwable) {
      withContext(NonCancellable) { imported?.let { delete(it) } }
      throw failure
    }
  }

  override suspend fun validate(uri: String) = validate(uri, requirePersonalFormat = false)

  private suspend fun validate(uri: String, requirePersonalFormat: Boolean) =
      withContext(Dispatchers.IO) {
        val file = ownedFile(uri)
        if (!file.isFile || file.length() !in 1..MAX_INPUT_PHOTO_BYTES)
            throw IOException("Image is unavailable")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
            throw IOException("Image cannot be decoded")
        if (bounds.outWidth.toLong() * bounds.outHeight > MAX_INPUT_PHOTO_PIXELS)
            throw IOException("Image exceeds pixel limit")
        if (requirePersonalFormat &&
            bounds.outMimeType != PhotoMimeTypes.JPEG &&
            bounds.outMimeType != PhotoMimeTypes.PNG)
            throw IOException("Personal photos must be JPEG or PNG")
      }

  override suspend fun delete(uri: String) =
      withContext(Dispatchers.IO) {
        // Never delete the original gallery image or any path outside our private capture
        // directory.
        runCatching { ownedFile(uri).delete() }
        Unit
      }

  private fun ownedFile(uri: String): File = requireOwnedPhotoFile(Uri.parse(uri), directory)
}

// Restored previews can still point to this directory after an app update.
internal fun temporaryPhotoDirectory(context: Context): File =
    File(context.applicationContext.cacheDir, "barcode-photos")

internal fun requireOwnedPhotoFile(uri: Uri, directory: File): File {
  require(uri.scheme == "file")
  val file = File(requireNotNull(uri.path)).canonicalFile
  require(file.parentFile == directory.canonicalFile)
  return file
}
