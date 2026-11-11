// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PhotoCopyTest {
  @Test
  fun copiesExactlyTheByteLimitAcrossBuffers() {
    val bytes = ByteArray(DEFAULT_BUFFER_SIZE + 1) { it.toByte() }
    val output = ByteArrayOutputStream()
    bytes.inputStream().copyPhotoTo(output, bytes.size.toLong(), checkActive = {}) {
      IOException("Image exceeds size limit")
    }
    assertArrayEquals(bytes, output.toByteArray())
  }

  @Test
  fun rejectsOversizedInputAfterReadingOnlyOneExtraByte() {
    val input = ByteArray(5).inputStream()
    val output = ByteArrayOutputStream()
    val tooLarge = IOException("Image exceeds size limit")
    val failure =
        assertThrows(IOException::class.java) {
          input.copyPhotoTo(output, 2, checkActive = {}) { tooLarge }
        }
    assertSame(tooLarge, failure)
    assertEquals(2, input.available())
    assertEquals(0, output.size())
  }

  @Test
  fun checksCancellationBeforeReading() {
    val input = byteArrayOf(42).inputStream()
    val output = ByteArrayOutputStream()
    val cancelled = CancellationException("Photo copy cancelled")
    val failure =
        assertThrows(CancellationException::class.java) {
          input.copyPhotoTo(output, 1, checkActive = { throw cancelled }) {
            IOException("Image exceeds size limit")
          }
        }
    assertSame(cancelled, failure)
    assertEquals(1, input.available())
    assertEquals(0, output.size())
  }
}
