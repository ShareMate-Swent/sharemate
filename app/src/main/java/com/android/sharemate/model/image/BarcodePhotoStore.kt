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
  suspend fun createCapture(): String

  suspend fun importImage(uri: String): String

  suspend fun validate(uri: String)

  suspend fun delete(uri: String)
}

class AndroidBarcodePhotoStore(context: Context) : BarcodePhotoStore {
  private val resolver = context.applicationContext.contentResolver
  private val directory = File(context.applicationContext.cacheDir, "barcode-photos")

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
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
              ensureActive()
              val count = input.read(buffer)
              if (count < 0) break
              total += count
              if (total > MAX_BYTES) throw IOException("Image exceeds size limit")
              output.write(buffer, 0, count)
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
      }

  override suspend fun delete(uri: String) =
      withContext(Dispatchers.IO) {
        // Never delete the original gallery image or any path outside our private capture
        // directory.
        runCatching { ownedFile(uri).delete() }
        Unit
      }

  private fun ownedFile(uri: String): File {
    val parsed = Uri.parse(uri)
    require(parsed.scheme == "file")
    val file = File(requireNotNull(parsed.path)).canonicalFile
    require(file.parentFile == directory.canonicalFile)
    return file
  }

  companion object {
    const val MAX_BYTES = 20L * 1024 * 1024
  }
}
