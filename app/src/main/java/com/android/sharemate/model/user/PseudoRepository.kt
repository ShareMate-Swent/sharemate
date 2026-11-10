// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.user

import kotlinx.coroutines.flow.Flow

/** Longest pseudo, in characters, that can be saved; `firestore.rules` enforces the same limit. */
const val MAX_PSEUDO_LENGTH = 30

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
   * Saves the trimmed, non-blank [pseudo] of at most [MAX_PSEUDO_LENGTH] characters as the public
   * profile's display name, preserving its other fields. Account creation and settings should use
   * this method.
   */
  suspend fun setPseudo(userId: String, pseudo: String)
}
