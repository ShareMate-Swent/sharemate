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
import java.util.Base64
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowBitmapFactory

@RunWith(RobolectricTestRunner::class)
class TemporaryPhotoStoreTest {
  private val context = RuntimeEnvironment.getApplication()
  private val store = AndroidTemporaryPhotoStore(context)
  private val directory = File(context.cacheDir, "barcode-photos")

  @Before
  fun enforceRealisticDecoderValidation() {
    // Legacy Robolectric otherwise accepts arbitrary bytes as an image, unlike Android.
    ShadowBitmapFactory.setAllowInvalidImageData(false)
  }

  @Test
  fun importsReadablePrivateCopyAndNeverDeletesGalleryOriginal() = runTest {
    val source = File(context.cacheDir, "original.png").apply { writeBytes(png()) }
    val selected = Uri.parse("content://photos/selected")
    shadowOf(context.contentResolver).registerInputStream(selected, source.inputStream())
    val copy = store.importImage(selected.toString())
    val copiedFile = File(Uri.parse(copy).path!!)
    assertEquals(directory.canonicalFile, copiedFile.canonicalFile.parentFile)
    assertArrayEquals(source.readBytes(), copiedFile.readBytes())
    store.validate(copy)
    store.delete(selected.toString())
    assertTrue(source.exists())
    store.delete(copy)
    assertFalse(copiedFile.exists())
    assertTrue(source.exists())
  }

  @Test
  fun fileUrisAndNetworkUrisAreNotAcceptedAsGalleryInput() = runTest {
    for (uri in listOf("file:///private/secret", "https://example.org/image.png")) {
      try {
        store.importImage(uri)
        fail("Only content selected by the Android picker is imported")
      } catch (_: IllegalArgumentException) {}
    }
    assertTrue(directory.listFiles().orEmpty().isEmpty())
  }

  @Test
  fun deleteAndValidationCannotEscapePrivateDirectory() = runTest {
    val outside = File(context.cacheDir, "unrelated.png").apply { writeBytes(png()) }
    directory.mkdirs()
    val paths =
        listOf(Uri.fromFile(outside).toString(), "file://${directory.path}/../unrelated.png")
    for (path in paths) {
      store.delete(path)
      assertTrue(outside.exists())
      try {
        store.validate(path)
        fail("A path outside the capture directory must be rejected")
      } catch (_: IllegalArgumentException) {}
    }
  }

  @Test
  fun emptyMalformedAndInterruptedImportsRemovePartialCopies() = runTest {
    val sources =
        listOf(
            ByteArrayInputStream(byteArrayOf()),
            ByteArrayInputStream("not an image".toByteArray()),
            object : InputStream() {
              override fun read(): Int = throw IOException("provider failed")
            },
            object : InputStream() {
              override fun read(): Int = throw CancellationException("cancel import")
            })
    for ((index, input) in sources.withIndex()) {
      val source = Uri.parse("content://photos/failure/$index")
      shadowOf(context.contentResolver).registerInputStream(source, input)
      try {
        store.importImage(source.toString())
        fail("Invalid or interrupted image import must fail")
      } catch (_: IOException) {} catch (_: CancellationException) {}
      assertTrue("Partial private files must be removed", directory.listFiles().orEmpty().isEmpty())
    }
  }

  @Test
  fun imageSizeLimitAcceptsBoundaryAndRejectsNextByteWithoutLeakingCopy() = runTest {
    for (extra in listOf(0L, 1L)) {
      val source = Uri.parse("content://photos/boundary/$extra")
      shadowOf(context.contentResolver)
          .registerInputStream(source, PaddedImage(png(), MAX_INPUT_PHOTO_BYTES + extra))
      if (extra == 0L) {
        val copy = store.importImage(source.toString())
        assertEquals(MAX_INPUT_PHOTO_BYTES, File(Uri.parse(copy).path!!).length())
        store.delete(copy)
      } else {
        try {
          store.importImage(source.toString())
          fail("Oversized image must not be accepted")
        } catch (failure: IOException) {
          assertFalse(failure is PhotoImportValidationException)
        }
      }
      assertTrue(directory.listFiles().orEmpty().isEmpty())
    }
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun personalGalleryRejectsOversizedUnsupportedAndTruncatedImagesBeforePreview() = runTest {
    val personalStore = AndroidTemporaryPhotoStore(context, PhotoImportPolicy.PERSONAL)
    val limit = PersonalPhotoRepository.MAX_BYTES.toLong()
    val random = Random(0)
    val pixels = IntArray(32 * 32) { random.nextInt() }
    val png = png(Bitmap.createBitmap(pixels, 32, 32, Bitmap.Config.ARGB_8888))
    val truncatedPng = png.copyOf(png.size / 2)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(truncatedPng, 0, truncatedPng.size, bounds)
    assertEquals(PhotoMimeTypes.PNG, bounds.outMimeType)
    assertTrue(bounds.outWidth > 0 && bounds.outHeight > 0)
    val gif =
        Base64.getDecoder().decode("R0lGODlhAQABAPAAAP///wAAACH5BAAAAAAALAAAAAABAAEAAAICRAEAOw==")
    val sources =
        mapOf(
            "valid" to PaddedImage(png, limit),
            "too-large" to PaddedImage(png, limit + 1),
            "truncated" to truncatedPng.inputStream(),
            "gif" to gif.inputStream())
    for ((name, stream) in sources) {
      val uri = Uri.parse("content://photos/personal/$name")
      shadowOf(context.contentResolver).registerInputStream(uri, stream)
      if (name == "valid") {
        val copy = personalStore.importImage(uri.toString())
        assertEquals(limit, File(Uri.parse(copy).path!!).length())
        personalStore.delete(copy)
      } else {
        val failure = runCatching { personalStore.importImage(uri.toString()) }.exceptionOrNull()
        assertEquals(
            "Expected import to reject $name before preview",
            PhotoImportRejection.PERSONAL_REQUIREMENTS,
            (failure as? PhotoImportValidationException)?.rejection)
      }
      assertTrue(directory.listFiles().orEmpty().isEmpty())
    }
    val receipt = Uri.parse("content://photos/receipt/gif")
    shadowOf(context.contentResolver).registerInputStream(receipt, gif.inputStream())
    val receiptCopy = store.importImage(receipt.toString())
    assertArrayEquals(gif, File(Uri.parse(receiptCopy).path!!).readBytes())
    store.delete(receiptCopy)
  }

  @Test
  fun personalGalleryKeepsProviderReadErrorsGeneric() = runTest {
    val source = Uri.parse("content://photos/personal/unavailable")
    val readError = IOException("Gallery unavailable")
    shadowOf(context.contentResolver)
        .registerInputStream(
            source,
            object : InputStream() {
              override fun read(): Int = throw readError
            })
    val personalStore = AndroidTemporaryPhotoStore(context, PhotoImportPolicy.PERSONAL)
    val failure = runCatching { personalStore.importImage(source.toString()) }.exceptionOrNull()
    // Coroutine stack-trace recovery can copy the exception when returning from Dispatchers.IO.
    assertEquals(IOException::class.java, failure?.javaClass)
    assertEquals(readError.message, failure?.message)
    assertTrue(directory.listFiles().orEmpty().isEmpty())
  }

  @Test
  fun emptyAndMissingCapturedImagesCannotBeConfirmed() = runTest {
    val capture = store.createCapture()
    try {
      store.validate(capture)
      fail("Empty capture must not be accepted")
    } catch (_: IOException) {}
    store.delete(capture)
    try {
      store.validate(capture)
      fail("Deleted capture must not be accepted")
    } catch (_: IOException) {}
  }

  @Test
  @Config(shadows = [BoundsOnlyDecoder::class])
  fun pixelLimitAcceptsBoundaryAndRejectsOversizedMetadataWithoutLeakingCopies() = runTest {
    val limit = MAX_INPUT_PHOTO_PIXELS.toInt()
    for ((width, height) in
        listOf(1 to limit - 1, 1 to limit, 1 to limit + 1, Int.MAX_VALUE to Int.MAX_VALUE)) {
      BoundsOnlyDecoder.width = width
      BoundsOnlyDecoder.height = height
      val selected = Uri.parse("content://photos/dimensions/$width/$height")
      val compressed = png()
      assertTrue(compressed.size < MAX_INPUT_PHOTO_BYTES)
      shadowOf(context.contentResolver).registerInputStream(selected, compressed.inputStream())
      if (width.toLong() * height <= MAX_INPUT_PHOTO_PIXELS) {
        val copy = store.importImage(selected.toString())
        assertTrue(File(Uri.parse(copy).path!!).exists())
        store.delete(copy)
      } else {
        try {
          store.importImage(selected.toString())
          fail("Oversized decoder dimensions must be rejected even for a small file")
        } catch (failure: IOException) {
          assertEquals("Image exceeds pixel limit", failure.message)
          assertFalse(failure is PhotoImportValidationException)
        }
        val personalStore = AndroidTemporaryPhotoStore(context, PhotoImportPolicy.PERSONAL)
        shadowOf(context.contentResolver).registerInputStream(selected, compressed.inputStream())
        val rejection =
            runCatching { personalStore.importImage(selected.toString()) }.exceptionOrNull()
        assertEquals(
            PhotoImportRejection.PIXEL_LIMIT,
            (rejection as? PhotoImportValidationException)?.rejection)
      }
      assertTrue(
          "No rejected or deleted copies should remain", directory.listFiles().orEmpty().isEmpty())
    }
  }

  // Stub only decoder metadata: legacy Robolectric otherwise allocates all pixels even when
  // inJustDecodeBounds is true. This keeps extreme dimensions and overflow tests memory-safe.
  @Implements(BitmapFactory::class)
  class BoundsOnlyDecoder {
    companion object {
      var width = 0
      var height = 0

      @JvmStatic
      @Implementation
      fun decodeFile(path: String, options: BitmapFactory.Options): Bitmap? {
        assertTrue(File(path).isFile)
        assertTrue("Validation must not decode pixels", options.inJustDecodeBounds)
        options.outWidth = width
        options.outHeight = height
        return null
      }
    }
  }

  private fun png(image: Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)): ByteArray {
    return ByteArrayOutputStream().use {
      image.compress(Bitmap.CompressFormat.PNG, 100, it)
      image.recycle()
      it.toByteArray()
    }
  }

  private class PaddedImage(private val header: ByteArray, private val size: Long) : InputStream() {
    private var position = 0L

    override fun read(): Int {
      if (position >= size) return -1
      val byte = if (position < header.size) header[position.toInt()].toInt() and 255 else 0
      position++
      return byte
    }
  }
}
