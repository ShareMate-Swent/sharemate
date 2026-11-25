// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.CancellationSignal
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Stages one provider stream so bounds, EXIF, and pixels all come from the same bytes. A content
 * URI can still require a download. The deadline bounds the caller's wait, even when a provider
 * ignores cancellation or blocks reading a pipe. Two shared workers and no waiting queue cap the
 * work such a provider can strand. Each worker owns its stream and temporary file until it exits.
 */
internal class BoundedOcrImageLoader(
    private val cacheDir: () -> File,
    private val open: (Uri, CancellationSignal) -> InputStream?,
) {
  constructor(
      context: Context
  ) : this(
      { context.cacheDir },
      { uri, signal ->
        context.contentResolver.openAssetFileDescriptor(uri, "r", signal)?.let { asset ->
          try {
            // Closing the returned stream also closes the asset's descriptor.
            asset.createInputStream()
          } catch (failure: Exception) {
            asset.close()
            throw failure
          }
        }
      },
  )

  suspend fun load(uri: Uri): Bitmap =
      withTimeoutOrNull(LOAD_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
          val signal = CancellationSignal()
          // Sending cancellation can itself call into the provider. Keep it off the caller thread.
          continuation.invokeOnCancellation { cancellationWorkers.execute { signal.cancel() } }
          try {
            workers.execute {
              if (!continuation.isActive) return@execute
              try {
                continuation.resume(stageAndDecode(uri, signal) { continuation.isActive })
              } catch (failure: Exception) {
                val error =
                    failure as? IOException ?: IOException("Could not load OCR image", failure)
                continuation.resumeWithException(error)
              }
            }
          } catch (failure: RejectedExecutionException) {
            continuation.resumeWithException(IOException("OCR image loader is busy", failure))
          }
        }
      } ?: throw IOException("OCR image loading timed out")

  private fun stageAndDecode(
      uri: Uri,
      signal: CancellationSignal,
      isActive: () -> Boolean,
  ): Bitmap {
    val file = File.createTempFile("ocr-", ".image", cacheDir())
    try {
      val source = open(uri, signal) ?: throw IOException("OCR image is unavailable")
      source.use { input ->
        file.outputStream().use { output ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          var size = 0
          while (true) {
            if (!isActive()) throw IOException("OCR image loading was cancelled")
            val count = input.read(buffer)
            if (count == -1) break
            size += count
            if (size > MAX_SOURCE_BYTES) throw IOException("OCR image file is too large")
            output.write(buffer, 0, count)
          }
        }
      }
      if (!isActive()) throw IOException("OCR image loading was cancelled")
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
    const val LOAD_TIMEOUT_MS = 5_000L
    const val MAX_SOURCE_BYTES = 32 * 1024 * 1024
    private const val WORKER_COUNT = 2

    private fun pool(name: String) =
        ThreadPoolExecutor(
            0,
            WORKER_COUNT,
            30L,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            { task -> Thread(task, name).apply { isDaemon = true } },
        )

    private val workers = pool("ocr-image-loader")
    // A saturated cancellation pool may drop the best-effort remote signal. The caller's
    // deadline and the loader worker limit still hold.
    private val cancellationWorkers =
        pool("ocr-image-cancellation").apply {
          rejectedExecutionHandler = ThreadPoolExecutor.DiscardPolicy()
        }
  }
}
