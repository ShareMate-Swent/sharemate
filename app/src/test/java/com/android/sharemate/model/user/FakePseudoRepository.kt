// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.user

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [PseudoRepository] for tests. */
class FakePseudoRepository(initial: Map<String, String> = emptyMap()) : PseudoRepository {
  private val stored = MutableStateFlow(initial)

  override fun observePseudos(userIds: Set<String>): Flow<Map<String, String>> =
      stored.map { all -> all.filterKeys { it in userIds } }

  override suspend fun getPseudo(userId: String): String? = stored.value[userId]

  override suspend fun setPseudo(userId: String, pseudo: String) {
    require(pseudo.isNotBlank())
    stored.value = stored.value + (userId to pseudo.trim())
  }
}
