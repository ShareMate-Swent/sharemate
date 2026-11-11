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
  fun galleryImagesRetainBytesDimensionsAndAlphaThroughThePrivateHandoff() = runTest {
    for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG)) {
      val original = File.createTempFile("gallery-", ".photo", context.cacheDir)
      val image = Bitmap.createBitmap(1600, 8, Bitmap.Config.ARGB_8888)
      image.setPixel(0, 0, 0x00000000)
      image.setPixel(1, 0, 0x804080c0.toInt())
      original.outputStream().use { assertTrue(image.compress(format, 100, it)) }
      image.recycle()
      val bytes = original.readBytes()
      val selected = Uri.parse("content://gallery/transparent")
      shadowOf(context.contentResolver).registerInputStream(selected, bytes.inputStream())
      val gallery = AndroidBarcodePhotoStore(context)
      val imported = gallery.importImage(selected.toString())
      var draft: File? = null
      try {
        assertEquals("file", Uri.parse(imported).scheme)
        draft = files.copy(imported)
        assertArrayEquals(bytes, draft.readBytes())
        val decoded = checkNotNull(BitmapFactory.decodeFile(draft.path))
        try {
          assertEquals(1600, decoded.width)
          assertEquals(8, decoded.height)
          if (format == Bitmap.CompressFormat.PNG) {
            assertEquals(0, android.graphics.Color.alpha(decoded.getPixel(0, 0)))
            assertEquals(128, android.graphics.Color.alpha(decoded.getPixel(1, 0)))
          }
        } finally {
          decoded.recycle()
        }
        gallery.delete(imported)
        assertTrue(draft.exists())
        assertArrayEquals(bytes, original.readBytes())
      } finally {
        draft?.let { files.delete(it) }
        gallery.delete(imported)
        original.delete()
      }
    }
  }

  @Test
  fun oversizedGalleryHandoffIsRejectedWithoutKeepingAPartialDraft() = runTest {
    val original = File.createTempFile("gallery-large-", ".png", context.cacheDir)
    val image = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    original.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    image.recycle()
    val size = PersonalPhotoRepository.MAX_BYTES + 1L
    RandomAccessFile(original, "rw").use { it.setLength(size) }
    val selected = Uri.parse("content://gallery/large")
    shadowOf(context.contentResolver).registerInputStream(selected, original.inputStream())
    val gallery = AndroidBarcodePhotoStore(context)
    val imported = gallery.importImage(selected.toString())
    try {
      assertEquals(size, File(requireNotNull(Uri.parse(imported).path)).length())
      val failure = runCatching { files.copy(imported) }.exceptionOrNull()
      assertTrue(failure is IllegalArgumentException)
      assertTrue(privateDirectory.listFiles().orEmpty().isEmpty())
      assertEquals(size, original.length())
    } finally {
      gallery.delete(imported)
      original.delete()
    }
  }

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
        val failure =
            runCatching { files.copy(Uri.fromFile(source).toString(), fromCamera = true) }
                .exceptionOrNull()
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
    val copy = files.copy(Uri.fromFile(source).toString(), fromCamera = true)
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

    val copy = files.copy(Uri.fromFile(source).toString(), fromCamera = true)
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
      assertTrue(
          runCatching { files.copy(Uri.fromFile(source).toString(), fromCamera = true) }.isFailure)
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
