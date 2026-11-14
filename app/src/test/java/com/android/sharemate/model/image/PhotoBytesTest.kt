// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.image

import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Unit tests for the JPEG/PNG signature detection and bounded read in [PhotoBytes]. */
class PhotoBytesTest {
  private val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
  private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())

  @Test
  fun pngAndJpegSignaturesAreDetected() {
    assertEquals(PhotoMimeTypes.PNG, PhotoBytes.contentType(png))
    assertEquals(PhotoMimeTypes.PNG, PhotoBytes.contentType(png + 42))
    assertEquals(PhotoMimeTypes.JPEG, PhotoBytes.contentType(jpeg))
    assertEquals(PhotoMimeTypes.JPEG, PhotoBytes.contentType(jpeg + 42))
  }

  @Test
  fun emptyTruncatedOrNonImageBytesAreRejected() {
    for (bytes in
        listOf(
            byteArrayOf(),
            png.copyOf(png.size - 1),
            jpeg.copyOf(jpeg.size - 1),
            "not a photo".toByteArray())) {
      assertThrows(IllegalArgumentException::class.java) { PhotoBytes.contentType(bytes) }
    }
  }

  @Test
  fun readBoundedAcceptsTheLimitAndRejectsOneByteMore() {
    val atLimit = ByteArray(PersonalPhotoRepository.MAX_BYTES)
    assertEquals(atLimit.size, PhotoBytes.readBounded(atLimit.inputStream()) {}.size)
    assertThrows(IllegalArgumentException::class.java) {
      PhotoBytes.readBounded(ByteArray(PersonalPhotoRepository.MAX_BYTES + 1).inputStream()) {}
    }
  }

  @Test
  fun readBoundedStopsWhenTheCallerIsCancelled() {
    assertThrows(CancellationException::class.java) {
      PhotoBytes.readBounded(png.inputStream()) { throw CancellationException("cancelled") }
    }
  }
}
