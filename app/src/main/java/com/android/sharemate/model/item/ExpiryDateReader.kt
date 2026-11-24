// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import java.time.LocalDate

/** Reads candidate expiry dates from a local camera/picker image without exposing Android APIs. */
fun interface ExpiryDateReader {
  /**
   * Returns distinct dates in recognized-text order, including past dates. An empty list means no
   * supported date was detected; image/recognition failures propagate, as does cancellation.
   *
   * Candidates are not necessarily expiry dates (a label may contain a production date). The caller
   * must let the user confirm or correct a candidate before saving. The caller owns the image; this
   * reader neither changes nor deletes it.
   */
  suspend fun read(imageUri: String): List<LocalDate>
}
