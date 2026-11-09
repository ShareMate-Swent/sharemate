// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.user

import kotlinx.coroutines.flow.Flow

/** Reads and writes the `pseudo` shown to other members instead of a user's Firebase id. */
interface PseudoRepository {
  /**
   * Emits the pseudo of each of [userIds] that has a non-blank one. Users without a pseudo, or
   * whose profile cannot be read, are absent from the map.
   */
  fun observePseudos(userIds: Set<String>): Flow<Map<String, String>>

  /** The user's pseudo, or null when none is set. */
  suspend fun getPseudo(userId: String): String?

  /**
   * Saves the [pseudo] after removing invisible padding, preserving the profile's other fields. The
   * result must contain a visible character and at most 40 Unicode code points.
   *
   * @throws IllegalArgumentException if the pseudo is invalid.
   */
  suspend fun setPseudo(userId: String, pseudo: String)
}
