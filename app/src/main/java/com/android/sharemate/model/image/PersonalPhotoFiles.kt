// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

interface PersonalPhotoFiles {
  suspend fun copy(uri: String): File

  suspend fun delete(file: File)
}

/** Private copies keep the gallery original and its URI out of the upload and persisted item. */
class AndroidPersonalPhotoFiles(context: Context) : PersonalPhotoFiles {
  private val resolver = context.applicationContext.contentResolver
  private val directory = File(context.applicationContext.cacheDir, "personal-photo-drafts")
  private val captures = File(context.applicationContext.cacheDir, "barcode-photos")

  override suspend fun copy(uri: String): File {
    var copy: File? = null
    try {
      return withContext(Dispatchers.IO) {
        val source = Uri.parse(uri)
        require(source.scheme == "content" || source.scheme == "file")
        check(directory.isDirectory || directory.mkdirs())
        val target = File.createTempFile("selection-", ".photo", directory)
        copy = target
        if (source.scheme == "file") {
          writeCapture(source, target)
          return@withContext target
        }
        checkNotNull(resolver.openInputStream(source)).use { input ->
          target.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = PersonalPhotoRepository.MAX_BYTES
            while (true) {
              ensureActive()
              val count = input.read(buffer, 0, minOf(buffer.size, remaining + 1))
              if (count < 0) break
              require(count <= remaining)
              output.write(buffer, 0, count)
              remaining -= count
            }
          }
        }
        // Verify that the image can be decoded without allocating its full size.
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(target)) { decoder, info, _ ->
              require(info.mimeType == "image/jpeg" || info.mimeType == "image/png")
              decoder.setTargetSize(1, 1)
            }
            .recycle()
        target
      }
    } catch (failure: Throwable) {
      withContext(NonCancellable) { copy?.let { delete(it) } }
      throw failure
    }
  }

  /** Only our camera's private files are accepted; gallery imports keep their existing contract. */
  private fun writeCapture(uri: Uri, target: File) {
    val source = File(requireNotNull(uri.path)).canonicalFile
    require(source.parentFile == captures.canonicalFile)
    require(source.length() in 1..AndroidBarcodePhotoStore.MAX_BYTES)
    val bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
          require(info.mimeType == "image/jpeg" || info.mimeType == "image/png")
          require(
              info.size.width.toLong() * info.size.height <= AndroidBarcodePhotoStore.MAX_PIXELS)
          val scale = minOf(1.0, 1280.0 / maxOf(info.size.width, info.size.height))
          decoder.setTargetSize(
              (info.size.width * scale).roundToInt().coerceAtLeast(1),
              (info.size.height * scale).roundToInt().coerceAtLeast(1))
          decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    try {
      target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
      require(target.length() in 1..PersonalPhotoRepository.MAX_BYTES.toLong())
    } finally {
      bitmap.recycle()
    }
  }

  override suspend fun delete(file: File) =
      withContext(Dispatchers.IO) {
        runCatching {
          val owned = file.canonicalFile
          if (owned.parentFile == directory.canonicalFile) owned.delete()
        }
        Unit
      }
}
