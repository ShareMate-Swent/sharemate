// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PersonalPhotoFilesTest {
  private val context = RuntimeEnvironment.getApplication()
  private val files = AndroidPersonalPhotoFiles(context)
  private val uri = Uri.parse("content://gallery/photo")
  private val privateDirectory = File(context.cacheDir, "personal-photo-drafts")

  @Test
  fun emptyOversizedAndUnsupportedCameraFilesLeaveNoDraft() = runTest {
    val captures = File(context.cacheDir, "barcode-photos").apply { mkdirs() }
    val empty = File.createTempFile("empty-", ".jpg", captures)
    val oversized =
        File.createTempFile("oversized-", ".jpg", captures).apply {
          RandomAccessFile(this, "rw").use { it.setLength(AndroidBarcodePhotoStore.MAX_BYTES + 1) }
        }
    val unsupported = File.createTempFile("unsupported-", ".webp", captures)
    val image = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    try {
      unsupported.outputStream().use {
        assertTrue(image.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it))
      }
      for (source in listOf(empty, oversized, unsupported)) {
        val failure = runCatching { files.copy(Uri.fromFile(source).toString()) }.exceptionOrNull()
        assertTrue("Rejected capture: ${source.name}", failure is IllegalArgumentException)
        assertTrue(privateDirectory.listFiles().orEmpty().isEmpty())
        assertTrue(source.exists())
      }
    } finally {
      image.recycle()
      listOf(empty, oversized, unsupported).forEach { it.delete() }
    }
  }

  @Test
  fun smallPortraitJpegIsNotUpscaledAndTheSourceIsPreserved() = runTest {
    val captures = File(context.cacheDir, "barcode-photos").apply { mkdirs() }
    val source = File.createTempFile("portrait-", ".jpg", captures)
    val image = Bitmap.createBitmap(8, 16, Bitmap.Config.ARGB_8888)
    source.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
    image.recycle()
    val original = source.readBytes()
    val copy = files.copy(Uri.fromFile(source).toString())
    try {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(copy.path, bounds)
      assertEquals("image/jpeg", bounds.outMimeType)
      assertEquals(8, bounds.outWidth)
      assertEquals(16, bounds.outHeight)
      assertArrayEquals(original, source.readBytes())
    } finally {
      files.delete(copy)
      source.delete()
    }
  }

  @Test
  fun cameraPhotoLargerThanFiveMiBIsResizedToJpegWithoutChangingSource() = runTest {
    val captures = File(context.cacheDir, "barcode-photos").apply { mkdirs() }
    val source = File.createTempFile("capture-", ".png", captures)
    val random = Random(0)
    val bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(
        IntArray(1600 * 1200) { random.nextInt() or (0xff shl 24) }, 0, 1600, 0, 0, 1600, 1200)
    source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    bitmap.recycle()
    val size = source.length()
    assertTrue(size > PersonalPhotoRepository.MAX_BYTES)

    val copy = files.copy(Uri.fromFile(source).toString())
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(copy.path, bounds)
    assertEquals("image/jpeg", bounds.outMimeType)
    assertEquals(1280, bounds.outWidth)
    assertEquals(960, bounds.outHeight)
    assertTrue(copy.length() in 1..PersonalPhotoRepository.MAX_BYTES.toLong())
    assertEquals(size, source.length())
    files.delete(copy)
    assertTrue(source.exists())
    source.delete()
  }

  @Test
  fun cameraImportRejectsFilesOutsideItsDirectoryAndUnreadableCaptures() = runTest {
    val captures = File(context.cacheDir, "barcode-photos").apply { mkdirs() }
    val invalid = File.createTempFile("capture-", ".jpg", captures).apply { writeText("broken") }
    val outside = File(context.cacheDir, "outside.jpg").apply { writeText("private") }
    for (source in listOf(invalid, outside)) {
      assertTrue(runCatching { files.copy(Uri.fromFile(source).toString()) }.isFailure)
      assertTrue(privateDirectory.listFiles().orEmpty().isEmpty())
      assertTrue(source.exists())
      source.delete()
    }
  }

  @Test
  fun jpegAndPngCopiesPreserveTheOriginal() = runTest {
    for (format in listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.PNG)) {
      val original = File(context.cacheDir, "original-${format.name}")
      val bitmap = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888)
      original.outputStream().use { assertTrue(bitmap.compress(format, 100, it)) }
      bitmap.recycle()
      val bytes = original.readBytes()
      shadowOf(context.contentResolver).registerInputStream(uri, original.inputStream())
      val copy = files.copy(uri.toString())
      assertEquals(privateDirectory.canonicalFile, copy.canonicalFile.parentFile)
      assertArrayEquals(bytes, copy.readBytes())
      files.delete(copy)
      files.delete(original)
      assertFalse(copy.exists())
      assertArrayEquals(bytes, original.readBytes())
    }
  }

  @Test
  fun oversizedAndUnreadableImportsLeaveNoPrivateCopy() = runTest {
    for (bytes in listOf(ByteArray(PersonalPhotoRepository.MAX_BYTES + 1), byteArrayOf(42))) {
      shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())
      assertTrue(runCatching { files.copy(uri.toString()) }.isFailure)
      assertTrue(privateDirectory.listFiles().orEmpty().isEmpty())
    }
  }

  @Test
  fun rejectsUnsupportedUrisAndMissingStreamsWithoutLeavingDrafts() = runTest {
    shadowOf(context.contentResolver).registerInputStream(uri, null)
    val invalidSources =
        listOf("file:///photo.png", "file:opaque", "https://example.org/photo.png", uri.toString())
    for (source in invalidSources) {
      assertTrue(runCatching { files.copy(source) }.isFailure)
      assertTrue(privateDirectory.listFiles().orEmpty().isEmpty())
    }
  }

  @Test
  fun rejectsAReadableImageOfAnUnsupportedTypeAndCleansUpItsCopy() = runTest {
    val original = File(context.cacheDir, "original.webp")
    val bitmap = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888)
    original.outputStream().use {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it))
    }
    bitmap.recycle()
    shadowOf(context.contentResolver).registerInputStream(uri, original.inputStream())

    val failure = runCatching { files.copy(uri.toString()) }.exceptionOrNull()

    assertTrue(failure is IllegalArgumentException)
    assertTrue(privateDirectory.listFiles().orEmpty().isEmpty())
    assertTrue(original.exists())
  }
}
