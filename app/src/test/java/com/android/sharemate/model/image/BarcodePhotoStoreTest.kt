package com.android.sharemate.model.image

import android.graphics.Bitmap
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBitmapFactory

@RunWith(RobolectricTestRunner::class)
class BarcodePhotoStoreTest {
  private val context = RuntimeEnvironment.getApplication()
  private val store = AndroidBarcodePhotoStore(context)
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
          .registerInputStream(
              source, PaddedImage(png(), AndroidBarcodePhotoStore.MAX_BYTES + extra))
      if (extra == 0L) {
        val copy = store.importImage(source.toString())
        assertEquals(AndroidBarcodePhotoStore.MAX_BYTES, File(Uri.parse(copy).path!!).length())
        store.delete(copy)
      } else {
        try {
          store.importImage(source.toString())
          fail("Oversized image must not be accepted")
        } catch (_: IOException) {}
      }
      assertTrue(directory.listFiles().orEmpty().isEmpty())
    }
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

  private fun png(): ByteArray {
    val image = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
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
