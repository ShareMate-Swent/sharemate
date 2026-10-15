// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

/** Image lookup policy and HTTP transport limits, in milliseconds. */
internal object FoodImageTimeouts {
  // Limits how long item creation waits for an optional image, excluding Firebase saving.
  const val IMAGE_LOOKUP_TIMEOUT_MS = 20_000L

  // Transport safeguards also apply when the repository is used without the ViewModel.
  const val HTTP_CONNECT_TIMEOUT_MS = 20_000L
  const val HTTP_READ_TIMEOUT_MS = 20_000L
  const val HTTP_CALL_TIMEOUT_MS = 20_000L
}
