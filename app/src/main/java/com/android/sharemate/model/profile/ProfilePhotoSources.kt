// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.profile

import java.net.URI
import java.net.URISyntaxException

/** Google account photos, e.g. `lh3.googleusercontent.com`. */
private val GOOGLE_PHOTO_HOST = Regex("lh[0-9]+\\.googleusercontent\\.com", RegexOption.IGNORE_CASE)

/** Host of Firebase Storage download URLs. */
private const val STORAGE_HOST = "firebasestorage.googleapis.com"

/**
 * Returns [url] if it points to a photo source ShareMate supports, or null so the UI shows the
 * placeholder instead.
 *
 * Members can save any string as their own profile's photo URL, and loading another member's photo
 * sends a request to that address. Without this check, a member using a modified client could point
 * it at their own server and learn when, and from which IP address, their housemates open the
 * members list.
 *
 * Supported are Google account photos, and the profile photos [uid] uploaded to [photoBucket], our
 * Storage bucket: a photo in another bucket or another member's folder is rejected. The host is
 * read from the parsed URL, so a look-alike such as
 * `https://lh3.googleusercontent.com@evil.example/` is rejected.
 *
 * @param uid The member the photo belongs to.
 * @param photoBucket Our Storage bucket; null accepts Google account photos only.
 */
fun supportedPhotoUrl(url: String?, uid: String, photoBucket: String?): String? {
  if (url.isNullOrBlank()) return null
  val uri =
      try {
        URI(url)
      } catch (_: URISyntaxException) {
        return null
      }
  val isHttps = uri.scheme.equals("https", ignoreCase = true)
  val isGooglePhoto = uri.host?.let(GOOGLE_PHOTO_HOST::matches) == true
  return url.takeIf { isHttps && (isGooglePhoto || isOwnUpload(uri, url, uid, photoBucket)) }
}

private fun isOwnUpload(uri: URI, url: String, uid: String, photoBucket: String?): Boolean {
  if (photoBucket == null || !uri.host.equals(STORAGE_HOST, ignoreCase = true)) return false
  val folder = runCatching { ProfilePhotoPaths.folder(uid) }.getOrNull() ?: return false
  val path = ProfilePhotoPaths.objectPath(url, photoBucket) ?: return false
  val name = path.removePrefix("$folder/")
  return name != path && runCatching { ProfilePhotoPaths.avatar(uid, name) }.isSuccess
}
