// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.CancellationSignal
import com.android.sharemate.model.image.BoundedPhotoRead
import com.android.sharemate.model.image.copyPhotoTo
import com.android.sharemate.model.image.openPhotoStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Stages one provider stream so bounds, EXIF, and pixels all come from the same bytes. */
internal class BoundedOcrImageLoader(
    private val cacheDir: () -> File,
    private val open: (Uri, CancellationSignal) -> InputStream?,
) {
  constructor(
      context: Context
  ) : this({ context.cacheDir }, context.contentResolver::openPhotoStream)

  suspend fun load(uri: Uri): Bitmap =
      BoundedPhotoRead.run(
          timeoutMessage = "OCR image loading timed out",
          busyMessage = "OCR image loader is busy",
          discard = Bitmap::recycle,
      ) { signal, checkActive ->
        try {
          stageAndDecode(uri, signal, checkActive)
        } catch (failure: IOException) {
          throw failure
        } catch (failure: Exception) {
          throw IOException("Could not load OCR image", failure)
        }
      }

  private fun stageAndDecode(
      uri: Uri,
      signal: CancellationSignal,
      checkActive: () -> Unit,
  ): Bitmap {
    val file = File.createTempFile("ocr-", ".image", cacheDir())
    try {
      val source = open(uri, signal) ?: throw IOException("OCR image is unavailable")
      source.use { input ->
        file.outputStream().use { output ->
          input.copyPhotoTo(output, MAX_SOURCE_BYTES.toLong(), checkActive) {
            IOException("OCR image file is too large")
          }
        }
      }
      checkActive()
      return decode(file)
    } finally {
      file.delete()
    }
  }

  private fun decode(file: File): Bitmap {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) {
      throw IOException("OCR image has invalid dimensions")
    }

    val longSide = maxOf(options.outWidth, options.outHeight)
    var sample = 1
    while ((longSide.toLong() + sample - 1) / sample > MAX_LONG_SIDE) sample *= 2
    options.inSampleSize = sample
    options.inJustDecodeBounds = false

    val orientation =
        try {
          ExifInterface(file.path)
              .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: IOException) {
          // Some BitmapFactory formats have no EXIF support.
          ExifInterface.ORIENTATION_NORMAL
        }
    val bitmap =
        BitmapFactory.decodeFile(file.path, options)
            ?: throw IOException("Could not decode OCR image")
    val matrix = Matrix()
    when (orientation) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.setRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.setRotate(-90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
    }
    if (matrix.isIdentity) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
  }

  internal companion object {
    const val MAX_LONG_SIDE = 2048
    const val MAX_SOURCE_BYTES = 32 * 1024 * 1024
  }
}
