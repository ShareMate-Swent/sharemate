// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

/** Image lookup policy and HTTP transport limits, in milliseconds. */
internal object FoodImageTimeouts {
  // Bounds each optional image lookup attempt, excluding retry waits and Firebase writes.
  const val IMAGE_LOOKUP_TIMEOUT_MS = 20_000L
  const val IMAGE_LOOKUP_MAX_ATTEMPTS = 3
  const val IMAGE_LOOKUP_RETRY_DELAY_MS = 2_000L

  // Transport safeguards also apply when the repository is used without the ViewModel.
  const val HTTP_CONNECT_TIMEOUT_MS = 20_000L
  const val HTTP_READ_TIMEOUT_MS = 20_000L
  const val HTTP_CALL_TIMEOUT_MS = 20_000L
}
