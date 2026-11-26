// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.CancellationSignal
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

class AndroidTemporaryPhotoStore
internal constructor(
    context: Context,
    private val importPolicy: PhotoImportPolicy,
    private val open: (Uri, CancellationSignal) -> InputStream?,
) : TemporaryPhotoStore {
  constructor(
      context: Context,
      importPolicy: PhotoImportPolicy = PhotoImportPolicy.STANDARD,
  ) : this(context, importPolicy, context.applicationContext.contentResolver::openPhotoStream)

  private val directory = temporaryPhotoDirectory(context)

  override suspend fun createCapture(): String {
    var created: File? = null
    try {
      return withContext(Dispatchers.IO) {
        val file = createCaptureFile()
        created = file
        Uri.fromFile(file).toString()
      }
    } catch (failure: Throwable) {
      withContext(NonCancellable + Dispatchers.IO) { created?.delete() }
      throw failure
    }
  }

  override suspend fun importImage(uri: String): String {
    val source = Uri.parse(uri)
    require(source.scheme == "content")
    val imported =
        BoundedPhotoRead.run(
            timeoutMessage = "Gallery image import timed out",
            busyMessage = "Photo loader is busy",
            discard = { file: File -> file.delete() },
        ) { signal, checkActive ->
          val file = createCaptureFile()
          try {
            val stream = open(source, signal) ?: throw IOException("Image is unavailable")
            stream.use { input ->
              file.outputStream().use { output ->
                input.copyPhotoTo(output, importPolicy.maxBytes, checkActive) {
                  importPolicy.reject(
                      PhotoImportRejection.PERSONAL_REQUIREMENTS, "Image exceeds size limit")
                }
              }
            }
            checkActive()
            validateBounds(file, importPolicy)
            importPolicy.validateContent(file)
            file
          } catch (failure: Throwable) {
            file.delete()
            throw failure
          }
        }
    return Uri.fromFile(imported).toString()
  }

  private fun createCaptureFile(): File {
    if (!directory.isDirectory) directory.mkdirs()
    return File.createTempFile("capture-", ".jpg", directory)
  }

  override suspend fun validate(uri: String) =
      withContext(Dispatchers.IO) { validateBounds(ownedFile(uri)) }

  private fun validateBounds(file: File, policy: PhotoImportPolicy = PhotoImportPolicy.STANDARD) {
    if (!file.isFile || file.length() !in 1..MAX_INPUT_PHOTO_BYTES)
        throw IOException("Image is unavailable")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
        throw policy.reject(PhotoImportRejection.PERSONAL_REQUIREMENTS, "Image cannot be decoded")
    if (bounds.outWidth.toLong() * bounds.outHeight > MAX_INPUT_PHOTO_PIXELS)
        throw policy.reject(PhotoImportRejection.PIXEL_LIMIT, "Image exceeds pixel limit")
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
