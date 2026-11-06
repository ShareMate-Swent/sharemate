// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import java.io.File

/** A bucket-relative identifier, never a local URI or a bearer download URL. */
data class PersonalPhotoReference(val path: String) {
  init {
    require(Regex("itemPhotos/[^/]+/[a-f0-9-]{36}").matches(path))
    require(path.split('/').none { it == "." || it == ".." })
  }
}

/**
 * Storage for an existing item's photos. Authorization follows that item's current owner/household,
 * including PERSONAL items visible to household members. A photo stays bound to the item's original
 * owner; reusing the item ID as another owner does not grant access. No attachment to Item.image is
 * performed.
 *
 * Operations may run from the main thread and return only after server confirmation. Failures are
 * Result.failure; coroutine cancellation is thrown. Transfers are canceled when supported, but a
 * committed upload/delete cannot be undone by cancellation. No durable retry is provided.
 */
interface PersonalPhotoRepository {
  /**
   * Reads a caller-owned file without modifying/deleting it. JPEG/PNG signatures and a 5 MiB limit
   * are checked; this is not full image decoding. The caller must keep the file stable until
   * return.
   */
  suspend fun upload(itemId: String, source: File): Result<PersonalPhotoReference>

  /**
   * Authenticated, bounded download. Returned bytes belong to the caller; no disk cache is made.
   */
  suspend fun load(reference: PersonalPhotoReference): Result<ByteArray>

  /** Delete while the item still exists; deleting an item first requires administrative cleanup. */
  suspend fun delete(reference: PersonalPhotoReference): Result<Unit>

  /** Reads the saved item from the server and removes its attached Storage photo, if present. */
  suspend fun deleteAttached(itemId: String): Result<Unit>

  companion object {
    const val MAX_BYTES = 5 * 1024 * 1024
  }
}
