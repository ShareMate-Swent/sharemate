// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class PersonalPhotoDraftError {
  UNREADABLE,
  UNSUPPORTED,
  TOO_LARGE,
  INVALID
}

class PersonalPhotoDraftException(
    val reason: PersonalPhotoDraftError,
    cause: Throwable? = null,
) : Exception(reason.name, cause)

/** Owns private gallery copies; the consumer keeps each copy until its upload/retry is finished. */
interface PersonalPhotoDraftStore {
  /**
   * Imports a picker content URI as a stable JPEG/PNG file of at most 5 MiB. Never edits its
   * source.
   */
  suspend fun importPhoto(uri: String): File

  /**
   * Removes only a copy owned by this store. Unrelated files, including originals, are preserved.
   */
  suspend fun delete(file: File)
}

class AndroidPersonalPhotoDraftStore(context: Context) : PersonalPhotoDraftStore {
  private val resolver = context.applicationContext.contentResolver
  private val directory = File(context.applicationContext.cacheDir, "personal-photo-drafts")

  override suspend fun importPhoto(uri: String): File {
    var copy: File? = null
    try {
      return withContext(Dispatchers.IO) {
        val selected = Uri.parse(uri)
        if (selected.scheme != "content")
            throw PersonalPhotoDraftException(PersonalPhotoDraftError.UNREADABLE)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("No photo directory")
        val target = File.createTempFile("selection-", ".photo", directory)
        copy = target
        resolver.openInputStream(selected)?.use { input ->
          target.outputStream().use { output ->
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
              ensureActive()
              // Read at most one byte past the limit, without writing that byte into our copy.
              val count =
                  input.read(
                      buffer, 0, minOf(buffer.size, PersonalPhotoRepository.MAX_BYTES - total + 1))
              if (count < 0) break
              total += count
              if (total > PersonalPhotoRepository.MAX_BYTES)
                  throw PersonalPhotoDraftException(PersonalPhotoDraftError.TOO_LARGE)
              output.write(buffer, 0, count)
            }
          }
        } ?: throw PersonalPhotoDraftException(PersonalPhotoDraftError.UNREADABLE)
        ensureActive()
        validate(target)
        target
      }
    } catch (failure: Throwable) {
      withContext(NonCancellable + Dispatchers.IO) { copy?.delete() }
      when (failure) {
        is CancellationException -> throw failure
        is PersonalPhotoDraftException -> throw failure
        is Exception ->
            throw PersonalPhotoDraftException(PersonalPhotoDraftError.UNREADABLE, failure)
        else -> throw failure
      }
    }
  }

  override suspend fun delete(file: File) =
      withContext(Dispatchers.IO) {
        // Canonical paths also reject ../ and links that escape the owned directory.
        runCatching {
          val owned = file.canonicalFile
          if (owned.parentFile == directory.canonicalFile) owned.delete()
        }
        Unit
      }

  private fun validate(file: File) {
    if (file.length() == 0L) throw PersonalPhotoDraftException(PersonalPhotoDraftError.INVALID)
    val header = ByteArray(8)
    val count = file.inputStream().use { it.read(header) }
    val jpeg =
        count >= 3 &&
            header[0] == 0xff.toByte() &&
            header[1] == 0xd8.toByte() &&
            header[2] == 0xff.toByte()
    val png = header.contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
    if (!jpeg && !png) throw PersonalPhotoDraftException(PersonalPhotoDraftError.UNSUPPORTED)
    // Inspect metadata first, then decode only a thumbnail: valid headers can hide broken pixels.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
      BitmapFactory.decodeFile(file.path, bounds)
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
          throw PersonalPhotoDraftException(PersonalPhotoDraftError.INVALID)
      var sample = 1
      val longestSide = maxOf(bounds.outWidth, bounds.outHeight).toLong()
      while ((longestSide + sample - 1) / sample > 256) sample *= 2
      val thumbnail =
          BitmapFactory.decodeFile(
              file.path,
              BitmapFactory.Options().apply {
                inSampleSize = sample
                inScaled = false
              }) ?: throw PersonalPhotoDraftException(PersonalPhotoDraftError.INVALID)
      thumbnail.recycle()
    } catch (failure: Exception) {
      throw PersonalPhotoDraftException(PersonalPhotoDraftError.INVALID, failure)
    }
  }
}
