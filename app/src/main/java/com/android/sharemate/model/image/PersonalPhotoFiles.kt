// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
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

  override suspend fun copy(uri: String): File {
    var copy: File? = null
    try {
      return withContext(Dispatchers.IO) {
        val source = Uri.parse(uri)
        require(source.scheme == "content")
        check(directory.isDirectory || directory.mkdirs())
        val target = File.createTempFile("selection-", ".photo", directory)
        copy = target
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

  override suspend fun delete(file: File) =
      withContext(Dispatchers.IO) {
        runCatching {
          val owned = file.canonicalFile
          if (owned.parentFile == directory.canonicalFile) owned.delete()
        }
        Unit
      }
}
