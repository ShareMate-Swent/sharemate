// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.image

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Byte-level checks shared by the repositories that store photos in Firebase Storage. */
internal object PhotoBytes {
  private val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
  private val JPEG_SIGNATURE = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())

  /**
   * Reads [stream] fully, up to [PersonalPhotoRepository.MAX_BYTES]. The caller keeps ownership of
   * the stream and closes it.
   *
   * @param checkActive Called before each read so a cancelled caller stops early.
   * @throws IllegalArgumentException if the stream holds more than the limit.
   */
  fun readBounded(stream: InputStream, checkActive: () -> Unit): ByteArray {
    val output = ByteArrayOutputStream()
    stream.copyPhotoTo(output, PersonalPhotoRepository.MAX_BYTES.toLong(), checkActive) {
      IllegalArgumentException("Photo exceeds ${PersonalPhotoRepository.MAX_MEBIBYTES} MiB")
    }
    return output.toByteArray()
  }

  /**
   * Returns the media type of [bytes] from its file signature. This is not full image decoding.
   *
   * @throws IllegalArgumentException if [bytes] is empty or neither a JPEG nor a PNG.
   */
  fun contentType(bytes: ByteArray): String =
      when {
        bytes.startsWith(PNG_SIGNATURE) -> PhotoMimeTypes.PNG
        bytes.startsWith(JPEG_SIGNATURE) -> PhotoMimeTypes.JPEG
        else -> throw IllegalArgumentException("Expected a JPEG or PNG photo")
      }

  private fun ByteArray.startsWith(signature: ByteArray): Boolean =
      size >= signature.size && signature.indices.all { this[it] == signature[it] }
}
