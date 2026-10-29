// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

/** Outcome of an atomic, server-confirmed image attachment. */
sealed interface ItemImageUpdateResult {
  object Updated : ItemImageUpdateResult

  /** The item was deleted, renamed, or already has an image. No fields were written. */
  object Ignored : ItemImageUpdateResult

  /** The eligibility check or write failed; this is not an ignored lookup result. */
  data class Error(val cause: Exception) : ItemImageUpdateResult
}
