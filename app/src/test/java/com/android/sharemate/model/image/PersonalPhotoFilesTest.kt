// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import android.graphics.Bitmap
import android.net.Uri
import java.io.File
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
}
