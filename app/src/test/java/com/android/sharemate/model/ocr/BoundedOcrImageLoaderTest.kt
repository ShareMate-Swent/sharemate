// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.CancellationSignal
import com.android.sharemate.model.image.BoundedPhotoRead
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoundedOcrImageLoaderTest {
  @get:Rule val temp = TemporaryFolder()
  private val uri = Uri.parse("content://photos/image")

  @Test
  fun samplesLargeImageAndAppliesExifRotationFromOneStream() = runBlocking {
    val cache = temp.newFolder()
    val source = jpeg(4096, 2048, ExifInterface.ORIENTATION_ROTATE_90)
    var opens = 0
    var openedUri: Uri? = null
    var worker: Thread? = null
    var closed = false
    val loader =
        BoundedOcrImageLoader({ cache }) { actualUri, _ ->
          openedUri = actualUri
          opens++
          worker = Thread.currentThread()
          object : InputStream() {
            private val input = source.inputStream()

            override fun read() = input.read()

            override fun read(buffer: ByteArray, offset: Int, length: Int) =
                input.read(buffer, offset, length)

            override fun close() {
              input.close()
              closed = true
            }
          }
        }

    val bitmap = loader.load(uri)
    assertEquals(1024, bitmap.width)
    assertEquals(BoundedOcrImageLoader.MAX_LONG_SIDE, bitmap.height)
    assertEquals(1, opens)
    assertEquals(uri, openedUri)
    assertNotSame(Thread.currentThread(), worker)
    assertTrue(closed)
    assertTrue(cache.listFiles().orEmpty().isEmpty())
  }

  @Test
  fun appliesAllExifOrientationsToThePixels() = runBlocking {
    val red = Color.RED
    val green = Color.GREEN
    val blue = Color.BLUE
    val white = Color.WHITE
    val expectedCorners =
        mapOf(
            ExifInterface.ORIENTATION_NORMAL to listOf(red, green, blue, white),
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL to listOf(green, red, white, blue),
            ExifInterface.ORIENTATION_ROTATE_180 to listOf(white, blue, green, red),
            ExifInterface.ORIENTATION_FLIP_VERTICAL to listOf(blue, white, red, green),
            ExifInterface.ORIENTATION_TRANSPOSE to listOf(red, blue, green, white),
            ExifInterface.ORIENTATION_ROTATE_90 to listOf(blue, red, white, green),
            ExifInterface.ORIENTATION_TRANSVERSE to listOf(white, green, blue, red),
            ExifInterface.ORIENTATION_ROTATE_270 to listOf(green, white, red, blue),
        )
    for ((orientation, corners) in expectedCorners) {
      val source = jpeg(64, 48, orientation)
      val loader = BoundedOcrImageLoader({ temp.root }) { _, _ -> source.inputStream() }
      val bitmap = loader.load(uri)
      try {
        val swapsAxes =
            orientation in ExifInterface.ORIENTATION_TRANSPOSE..ExifInterface.ORIENTATION_ROTATE_270
        assertEquals(if (swapsAxes) 48 else 64, bitmap.width)
        assertEquals(if (swapsAxes) 64 else 48, bitmap.height)
        val right = bitmap.width - 8
        val bottom = bitmap.height - 8
        val actualCorners =
            listOf(
                bitmap.getPixel(8, 8),
                bitmap.getPixel(right, 8),
                bitmap.getPixel(8, bottom),
                bitmap.getPixel(right, bottom),
            )
        for ((expected, actual) in corners.zip(actualCorners)) {
          val label = "EXIF orientation $orientation"
          assertEquals(label, Color.red(expected).toDouble(), Color.red(actual).toDouble(), 8.0)
          assertEquals(label, Color.green(expected).toDouble(), Color.green(actual).toDouble(), 8.0)
          assertEquals(label, Color.blue(expected).toDouble(), Color.blue(actual).toDouble(), 8.0)
        }
      } finally {
        bitmap.recycle()
      }
    }
  }

  @Test
  fun invalidOversizedAndUnavailableSourcesFailAndDeleteStagingFiles() = runBlocking {
    val cache = temp.newFolder()
    for (source in
        listOf(null, ByteArrayInputStream("bad image".toByteArray()), oversizedStream())) {
      val loader = BoundedOcrImageLoader({ cache }) { _, _ -> source }
      assertTrue(runCatching { loader.load(uri) }.exceptionOrNull() is IOException)
      assertTrue(cache.listFiles().orEmpty().isEmpty())
    }
  }

  @Test
  fun providerAccessErrorIsAnImageFailure() = runBlocking {
    val cache = temp.newFolder()
    val loader = BoundedOcrImageLoader({ cache }) { _, _ -> throw SecurityException("denied") }
    val failure = runCatching { loader.load(uri) }.exceptionOrNull()
    assertTrue(failure is IOException)
    // Coroutine stack-trace recovery can wrap the original IOException.
    assertTrue(generateSequence(failure) { it.cause }.any { it is SecurityException })
    assertTrue(cache.listFiles().orEmpty().isEmpty())
  }

  @Test
  fun blockedProvidersTimeOutOrCancelWithoutWaitingForTheirStreams() = runTest {
    val cache = temp.newFolder()
    val entered = CountDownLatch(2)
    val signaled = CountDownLatch(2)
    val release = CountDownLatch(1)
    val closed = CountDownLatch(2)
    val loader =
        BoundedOcrImageLoader({ cache }) { _, signal: CancellationSignal ->
          signal.setOnCancelListener { signaled.countDown() }
          object : InputStream() {
            override fun read(): Int {
              entered.countDown()
              release.await()
              return -1
            }

            override fun close() {
              closed.countDown()
            }
          }
        }

    try {
      val timed = async { runCatching { loader.load(uri) } }
      val cancelled = async { loader.load(uri) }
      runCurrent()
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      cancelled.cancelAndJoin()
      assertTrue(cancelled.isCancelled)
      advanceTimeBy(BoundedPhotoRead.TIMEOUT_MS - 1)
      assertFalse(timed.isCompleted)
      advanceTimeBy(1)
      runCurrent()
      assertEquals("OCR image loading timed out", timed.await().exceptionOrNull()?.message)
      assertTrue(signaled.await(5, TimeUnit.SECONDS))
      assertEquals(2L, closed.count)
      assertEquals(
          "OCR image loader is busy", runCatching { loader.load(uri) }.exceptionOrNull()?.message)
    } finally {
      release.countDown()
    }
    assertTrue(closed.await(5, TimeUnit.SECONDS))
  }

  private fun jpeg(width: Int, height: Int, orientation: Int): File {
    val source = temp.newFile()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.WHITE)
    val canvas = Canvas(bitmap)
    val paint = Paint()
    val middleX = width / 2f
    val middleY = height / 2f
    paint.color = Color.RED
    canvas.drawRect(0f, 0f, middleX, middleY, paint)
    paint.color = Color.GREEN
    canvas.drawRect(middleX, 0f, width.toFloat(), middleY, paint)
    paint.color = Color.BLUE
    canvas.drawRect(0f, middleY, middleX, height.toFloat(), paint)
    source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
    bitmap.recycle()
    ExifInterface(source.path).apply {
      setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
      saveAttributes()
    }
    return source
  }

  private fun oversizedStream(): InputStream =
      object : InputStream() {
        private var remaining = BoundedOcrImageLoader.MAX_SOURCE_BYTES + 1

        override fun read(): Int = error("Expected buffered reads")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
          if (remaining == 0) return -1
          val count = minOf(remaining, length)
          remaining -= count
          return count
        }
      }
}
