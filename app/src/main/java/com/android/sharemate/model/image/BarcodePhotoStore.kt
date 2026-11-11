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

/** Owns temporary copies only. A confirmed URI remains readable until its form is dismissed. */
interface BarcodePhotoStore {
  /**
   * Returns a private file URI for in-process CameraX output. Never send it to an external camera
   * intent; external sharing would require a FileProvider content URI instead.
   */
  suspend fun createCapture(): String

  suspend fun importImage(uri: String): String

  suspend fun validate(uri: String)

  suspend fun delete(uri: String)
}

class AndroidBarcodePhotoStore(context: Context) : BarcodePhotoStore {
  private val resolver = context.applicationContext.contentResolver
  private val directory = barcodePhotoDirectory(context)

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
            input.copyPhotoTo(output, MAX_BYTES, checkActive = { ensureActive() }) {
              IOException("Image exceeds size limit")
            }
          }
        } ?: throw IOException("Image is unavailable")
        validate(target)
        target
      }
    } catch (failure: Throwable) {
      withContext(NonCancellable) { imported?.let { delete(it) } }
      throw failure
    }
  }

  override suspend fun validate(uri: String) =
      withContext(Dispatchers.IO) {
        val file = ownedFile(uri)
        if (!file.isFile || file.length() !in 1..MAX_BYTES)
            throw IOException("Image is unavailable")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
            throw IOException("Image cannot be decoded")
        if (bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS)
            throw IOException("Image exceeds pixel limit")
      }

  override suspend fun delete(uri: String) =
      withContext(Dispatchers.IO) {
        // Never delete the original gallery image or any path outside our private capture
        // directory.
        runCatching { ownedFile(uri).delete() }
        Unit
      }

  private fun ownedFile(uri: String): File = requireOwnedPhotoFile(Uri.parse(uri), directory)

  companion object {
    const val MAX_BYTES = 20L * 1024 * 1024
    // Accept up to 50 MP photos while rejecting oversized compressed images before pixel decoding.
    // Consumers must still downsample to their required resolution before allocating a bitmap.
    const val MAX_PIXELS = 50_000_000L
  }
}

internal fun barcodePhotoDirectory(context: Context): File =
    File(context.applicationContext.cacheDir, "barcode-photos")

internal fun requireOwnedPhotoFile(uri: Uri, directory: File): File {
  require(uri.scheme == "file")
  val file = File(requireNotNull(uri.path)).canonicalFile
  require(file.parentFile == directory.canonicalFile)
  return file
}
