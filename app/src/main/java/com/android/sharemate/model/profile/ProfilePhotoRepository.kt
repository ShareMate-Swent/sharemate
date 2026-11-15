// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.profile

import java.io.File
import java.net.URI
import java.net.URLDecoder

/**
 * Stores the signed-in user's profile photo and makes it their account photo.
 *
 * Failures are returned as [Result.failure]; coroutine cancellation is thrown. A cancelled upload
 * cancels the transfer, but an upload the server already committed is not undone.
 *
 * Each upload is stored at a new path, and the previous account photo is deleted only once the
 * account photo URL points at the new one. So if the upload or the account update fails or is
 * cancelled, the previous photo and its URL keep working. Only that previous photo is deleted, so
 * overlapping uploads never delete each other's photo; photos they leave unused are kept.
 */
interface ProfilePhotoRepository {
  /**
   * Uploads [source] as the profile photo of the signed-in user, sets the returned download URL as
   * their account photo, then deletes their previous account photo.
   *
   * [source] belongs to the caller and is only read. JPEG/PNG signatures and the
   * [com.android.sharemate.model.image.PersonalPhotoRepository.MAX_BYTES] limit are checked before
   * anything is sent; this is not full image decoding.
   *
   * @param expectedUid The user the caller believes is signed in. The upload is refused if another
   *   user, or nobody, is signed in.
   * @return The download URL of the stored photo, or a failure: [IllegalArgumentException] for an
   *   invalid photo, [IllegalStateException] when [expectedUid] is not the signed-in user, or the
   *   data source's error when a later step fails.
   */
  suspend fun upload(expectedUid: String, source: File): Result<String>
}

/**
 * Storage paths of profile photos. Each upload gets its own path under the user's folder, so the
 * previous photo stays readable until the account points at the new one.
 */
internal object ProfilePhotoPaths {
  /** Top-level Storage folder that holds one folder per user. */
  const val USERS = "users"

  /** Folder inside a user's folder that holds their profile photos. */
  const val AVATARS = "avatars"

  /**
   * Returns the Storage folder that holds [uid]'s profile photos.
   *
   * @throws IllegalArgumentException if [uid] is blank, `.`, `..` or contains a `/`.
   */
  fun folder(uid: String): String {
    require(isSegment(uid)) { "Invalid user id" }
    return "$USERS/$uid/$AVATARS"
  }

  /**
   * Returns the Storage path of [uid]'s profile photo [photoId].
   *
   * @throws IllegalArgumentException if [uid] or [photoId] is blank, `.`, `..` or contains a `/`.
   */
  fun avatar(uid: String, photoId: String): String {
    require(isSegment(photoId)) { "Invalid photo id" }
    return "${folder(uid)}/$photoId"
  }

  /**
   * Returns the object path in [bucket] that the Storage download URL [url] points to, or `null` if
   * [url] is not a download URL (`.../v0/b/{bucket}/o/{path}`) of that bucket, such as a Google
   * account photo. The host is not checked, so emulator URLs work too.
   */
  fun objectPath(url: String, bucket: String): String? {
    val segments = runCatching { URI(url).rawPath }.getOrNull()?.split('/') ?: return null
    if (segments.size != DOWNLOAD_URL_SEGMENTS) return null
    val (empty, version, b, urlBucket, o) = segments
    if (empty.isNotEmpty() || version != "v0" || b != "b" || o != "o") return null
    if (URLDecoder.decode(urlBucket, UTF_8) != bucket) return null
    return URLDecoder.decode(segments.last().replace("+", "%2B"), UTF_8).ifEmpty { null }
  }

  private const val DOWNLOAD_URL_SEGMENTS = 6
  private const val UTF_8 = "UTF-8"

  private fun isSegment(value: String): Boolean =
      value.isNotBlank() && '/' !in value && value != "." && value != ".."
}
