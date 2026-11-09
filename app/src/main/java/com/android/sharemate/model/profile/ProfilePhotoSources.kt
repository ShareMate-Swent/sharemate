// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.profile

import java.net.URI
import java.net.URISyntaxException

/** Google account photos, e.g. `lh3.googleusercontent.com`. */
private val GOOGLE_PHOTO_HOST = Regex("lh[0-9]+\\.googleusercontent\\.com", RegexOption.IGNORE_CASE)

/**
 * Returns [url] if it points to a photo source ShareMate supports, or null so the UI shows the
 * placeholder instead.
 *
 * Members can save any string as their own profile's photo URL, and loading another member's photo
 * sends a request to that address. Without this check, a member using a modified client could point
 * it at their own server and learn when, and from which IP address, their housemates open the
 * members list.
 *
 * Today only Google account photos are supported. The host is read from the parsed URL, so a
 * look-alike such as `https://lh3.googleusercontent.com@evil.example/` is rejected.
 */
fun supportedPhotoUrl(url: String?): String? {
  if (url.isNullOrBlank()) return null
  val uri =
      try {
        URI(url)
      } catch (_: URISyntaxException) {
        return null
      }
  val isHttps = uri.scheme.equals("https", ignoreCase = true)
  val isGooglePhoto = uri.host?.let(GOOGLE_PHOTO_HOST::matches) == true
  return url.takeIf { isHttps && isGooglePhoto }
}
