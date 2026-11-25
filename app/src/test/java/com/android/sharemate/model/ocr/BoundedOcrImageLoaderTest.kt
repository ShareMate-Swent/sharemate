// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.CancellationSignal
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
  fun appliesExifReflectionToThePixels() = runBlocking {
    val source = jpeg(64, 64, ExifInterface.ORIENTATION_FLIP_HORIZONTAL)
    val loader = BoundedOcrImageLoader({ temp.root }) { _, _ -> source.inputStream() }
    val bitmap = loader.load(uri)
    val left = bitmap.getPixel(8, 32)
    val right = bitmap.getPixel(56, 32)
    assertTrue(Color.blue(left) > Color.red(left))
    assertTrue(Color.red(right) > Color.blue(right))
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
      advanceTimeBy(BoundedOcrImageLoader.LOAD_TIMEOUT_MS - 1)
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
    bitmap.eraseColor(Color.BLUE)
    Canvas(bitmap)
        .drawRect(0f, 0f, width / 2f, height.toFloat(), Paint().apply { color = Color.RED })
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
