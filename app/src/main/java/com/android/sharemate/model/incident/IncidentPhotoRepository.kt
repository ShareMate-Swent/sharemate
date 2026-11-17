// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.incident

import java.io.File

/**
 * Authenticated Storage access, independent of incident messages. Expected failures are returned;
 * coroutine cancellation is thrown. Transfers await the server and have no durable offline queue.
 * Cancelling cannot undo a transfer already committed; unused objects may need later admin cleanup.
 */
interface IncidentPhotoRepository {
  /**
   * Uploads a JPEG/PNG of at most 5 MiB. Signature checks are not full image decoding. The caller
   * keeps [source] stable until return; this operation never modifies or deletes it. The signed-in
   * UID must equal [expectedAuthorId]. Rules validate current membership in [householdId].
   */
  suspend fun upload(
      householdId: String,
      expectedAuthorId: String,
      source: File,
  ): Result<IncidentPhotoReference>

  /** Downloads at most 5 MiB through authenticated SDK access, never a public download URL. */
  suspend fun load(reference: IncidentPhotoReference): Result<ByteArray>
}
