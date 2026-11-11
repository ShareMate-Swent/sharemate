// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import java.io.File
import java.util.UUID

/** A bucket-relative identifier, never a local URI or a bearer download URL. */
data class PersonalPhotoReference(val path: String) {
  init {
    require(PATH_PATTERN.matches(path))
    require(path.split('/').none { it == "." || it == ".." })
  }

  companion object {
    private const val STORAGE_PREFIX = "itemPhotos/"
    private val PATH_PATTERN = Regex("${STORAGE_PREFIX}[^/]+/[a-f0-9-]{36}")

    internal fun forItem(itemId: String, photoId: UUID): PersonalPhotoReference =
        PersonalPhotoReference(itemPrefix(itemId) + photoId)

    /** Recognizes a Storage path before the caller validates the full reference. */
    internal fun hasStoragePrefix(path: String): Boolean = path.startsWith(STORAGE_PREFIX)

    internal fun hasItemPrefix(path: String, itemId: String): Boolean =
        path.startsWith(itemPrefix(itemId))

    private fun itemPrefix(itemId: String): String = "$STORAGE_PREFIX$itemId/"
  }
}

/**
 * Storage for an existing item's photos. Current household members can access shared item photos.
 * Personal item photos require current membership and ownership. A photo stays bound to the item's
 * original owner. Upload returns a reference for the caller to attach to the item.
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
