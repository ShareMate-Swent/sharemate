// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import java.io.InputStream
import java.io.OutputStream

/** Copies within the byte limit, reading one extra byte to detect an oversized source. */
internal fun InputStream.copyPhotoTo(
    output: OutputStream,
    maxBytes: Long,
    checkActive: () -> Unit,
    sizeLimitError: () -> Exception,
) {
  val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
  var remaining = maxBytes
  while (true) {
    checkActive()
    val bytesToRead = if (remaining < buffer.size) remaining.toInt() + 1 else buffer.size
    val count = read(buffer, 0, bytesToRead)
    if (count < 0) return
    if (count > remaining) throw sizeLimitError()
    output.write(buffer, 0, count)
    remaining -= count
  }
}
