// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.profile

import java.io.File

/**
 * Stores the signed-in user's profile photo and makes it their account photo.
 *
 * Failures are returned as [Result.failure]; coroutine cancellation is thrown. A cancelled upload
 * cancels the transfer, but an upload the server already committed is not undone.
 *
 * The photo is stored before the account photo URL is updated. If that later step fails or is
 * cancelled, Storage already holds the new photo while the account still has its previous URL, so
 * callers should offer a retry: uploading again writes the same path and finishes the update.
 */
interface ProfilePhotoRepository {
  /**
   * Uploads [source] as the profile photo of the signed-in user, replacing any previous one, and
   * sets the returned download URL as their account photo.
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

/** Storage paths of profile photos. Each user has a single photo, so a new upload replaces it. */
internal object ProfilePhotoPaths {
  /** Top-level Storage folder that holds one folder per user. */
  const val USERS = "users"

  /** File name of the profile photo inside a user's folder. */
  const val AVATAR = "avatar.jpg"

  /**
   * Returns the Storage path of [uid]'s profile photo.
   *
   * @throws IllegalArgumentException if [uid] is blank, `.`, `..` or contains a `/`.
   */
  fun avatar(uid: String): String {
    require(uid.isNotBlank() && '/' !in uid && uid != "." && uid != "..") { "Invalid user id" }
    return "$USERS/$uid/$AVATAR"
  }
}
