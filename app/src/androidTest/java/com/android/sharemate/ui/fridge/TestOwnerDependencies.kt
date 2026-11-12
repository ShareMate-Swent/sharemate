// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import com.android.sharemate.model.user.MemberNameRepository
import com.android.sharemate.model.user.PseudoRepository
import kotlinx.coroutines.flow.flowOf

internal fun testMemberNameRepository(names: Map<String, String> = emptyMap()) =
    object : MemberNameRepository {
      override fun observeNames(householdId: String, viewerId: String) = flowOf(names)

      override suspend fun publishOwnName(householdId: String, userId: String, authName: String?) {}
    }

internal fun testPseudoRepository() =
    object : PseudoRepository {
      override fun observePseudos(userIds: Set<String>) = flowOf(emptyMap<String, String>())

      override suspend fun getPseudo(userId: String): String? = null

      override suspend fun setPseudo(userId: String, pseudo: String) {
        error("unused")
      }
    }
