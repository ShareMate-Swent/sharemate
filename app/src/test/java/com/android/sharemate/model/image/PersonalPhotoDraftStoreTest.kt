// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBitmapFactory

@RunWith(RobolectricTestRunner::class)
class PersonalPhotoDraftStoreTest {
  private val context = RuntimeEnvironment.getApplication()
  private val store = AndroidPersonalPhotoDraftStore(context)
  private val directory = File(context.cacheDir, "personal-photo-drafts")

  @Before
  fun setUp() {
    directory.deleteRecursively()
    ShadowBitmapFactory.setAllowInvalidImageData(false)
  }

  @Test
  fun jpegAndPngBecomeIndependentCopiesAndTheGalleryOriginalIsPreserved() = runTest {
    for (format in listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.PNG)) {
      val source = File(context.cacheDir, "original-$format").apply { writeBytes(image(format)) }
      val input = TrackingStream(source.readBytes())
      val uri = register("valid/$format", input)
      val copy = store.importPhoto(uri)
      assertEquals(directory.canonicalFile, copy.canonicalFile.parentFile)
      assertArrayEquals(source.readBytes(), copy.readBytes())
      assertTrue(input.closed)
      store.delete(copy)
      assertFalse(copy.exists())
      assertTrue(source.exists())
      assertArrayEquals(image(format), source.readBytes())
    }
  }

  @Test
  fun emptyUnsupportedAndUndecodablePhotosHaveDistinctErrorsAndLeaveNoCopies() = runTest {
    for ((bytes, error) in
        listOf(
            byteArrayOf() to PersonalPhotoDraftError.INVALID,
            "GIF89a1234".toByteArray() to PersonalPhotoDraftError.UNSUPPORTED,
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 42) to
                PersonalPhotoDraftError.INVALID)) {
      val input = TrackingStream(bytes)
      assertFailure(error) { store.importPhoto(register(error.name, input)) }
      assertTrue(input.closed)
      assertTrue(directory.listFiles().orEmpty().isEmpty())
    }
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun readableDimensionsDoNotMakeCorruptPixelDataAValidSelection() = runTest {
    val bytes = image()
    val marker = "IDAT".toByteArray().toList()
    val dataOffset = bytes.toList().windowed(4).indexOfFirst { it == marker } + 4
    assertTrue(dataOffset >= 4)
    // Break the compressed pixels while retaining a valid PNG signature and dimensions.
    bytes[dataOffset] = (bytes[dataOffset].toInt() xor 0xff).toByte()
    val original = File(context.cacheDir, "broken-pixels.png").apply { writeBytes(bytes) }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(original.path, bounds)
    assertTrue(bounds.outWidth > 0 && bounds.outHeight > 0)
    val input = TrackingStream(bytes)
    assertFailure(PersonalPhotoDraftError.INVALID) {
      store.importPhoto(register("corrupt-pixels", input))
    }
    assertTrue(input.closed)
    assertTrue(directory.listFiles().orEmpty().isEmpty())
    assertArrayEquals(bytes, original.readBytes())
  }

  @Test
  fun exactSizeIsAcceptedButOversizedImportsReadOnlyOneExtraByte() = runTest {
    for (extra in listOf(0, 1, 100_000)) {
      val input =
          TrackingStream(
              ByteArray(PersonalPhotoRepository.MAX_BYTES + extra).also { image().copyInto(it) })
      val uri = register("size/$extra", input)
      if (extra == 0) {
        val copy = store.importPhoto(uri)
        assertEquals(PersonalPhotoRepository.MAX_BYTES.toLong(), copy.length())
        store.delete(copy)
      } else {
        assertFailure(PersonalPhotoDraftError.TOO_LARGE) { store.importPhoto(uri) }
        assertEquals(PersonalPhotoRepository.MAX_BYTES + 1, input.readCount)
      }
      assertTrue(input.closed)
      assertTrue(directory.listFiles().orEmpty().isEmpty())
    }
  }

  @Test
  fun inaccessibleSourcesAndNonPickerUrisDoNotLeaveCopies() = runTest {
    val input =
        object : InputStream() {
          var closed = false

          override fun read(): Int = throw IOException("The provider is unavailable")

          override fun close() {
            closed = true
          }
        }
    assertFailure(PersonalPhotoDraftError.UNREADABLE) {
      store.importPhoto(register("unavailable", input))
    }
    assertTrue(input.closed)
    for (uri in listOf("file:///private/original.png", "https://example.org/image.png", "")) {
      assertFailure(PersonalPhotoDraftError.UNREADABLE) { store.importPhoto(uri) }
    }
    assertTrue(directory.listFiles().orEmpty().isEmpty())
  }

  @Test
  fun cancellationClosesTheProviderAndDeletesThePartialCopy() = runTest {
    val started = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val input =
        object : InputStream() {
          var closed = false

          override fun read(): Int = error("Use buffered reads")

          override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            started.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS))
            buffer[offset] = 42
            return 1
          }

          override fun close() {
            closed = true
          }
        }
    val pending = async { store.importPhoto(register("cancel", input)) }
    try {
      started.await()
      pending.cancel()
    } finally {
      release.countDown()
    }
    pending.cancelAndJoin()
    assertTrue(pending.isCancelled)
    assertTrue(input.closed)
    assertTrue(directory.listFiles().orEmpty().isEmpty())
  }

  @Test
  fun deletionPreservesFilesOutsideTheOwnedDirectoryIncludingTraversalPaths() = runTest {
    directory.mkdirs()
    val original = File(context.cacheDir, "kept.png").apply { writeBytes(image()) }
    store.delete(original)
    store.delete(File(directory, "../kept.png"))
    assertTrue(original.exists())
    assertArrayEquals(image(), original.readBytes())
  }

  private fun register(path: String, input: InputStream): String {
    val uri = Uri.parse("content://personal-photos/$path")
    shadowOf(context.contentResolver).registerInputStream(uri, input)
    return uri.toString()
  }

  private suspend fun assertFailure(error: PersonalPhotoDraftError, action: suspend () -> Unit) {
    try {
      action()
      fail("Expected $error")
    } catch (failure: PersonalPhotoDraftException) {
      assertEquals(error, failure.reason)
    }
  }

  private fun image(format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
    val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    return ByteArrayOutputStream().use {
      bitmap.compress(format, 100, it)
      bitmap.recycle()
      it.toByteArray()
    }
  }

  private class TrackingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
    val readCount
      get() = pos

    var closed = false

    override fun close() {
      closed = true
      super.close()
    }
  }
}
