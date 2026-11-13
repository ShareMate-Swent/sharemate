// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import com.android.sharemate.model.user.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*

internal class TestMemberNames(initial: Map<String, String> = emptyMap()) : MemberNameRepository {
  val names = MutableStateFlow(initial)
  var failure: Exception? = null
  var publicationFailure: Exception? = null
  var confirmation = CompletableDeferred(Result.success(Unit))
  val published = mutableListOf<String?>()
  var observations = 0
  var finite = false
  var queueGate: CompletableDeferred<Unit>? = null
  var queueAttempts = 0
  var cancelledQueues = 0

  override fun observeNames(householdId: String, viewerId: String) = flow {
    observations++
    failure?.let { throw it }
    if (finite) emit(names.value) else emitAll(names)
  }

  override suspend fun publishOwnName(householdId: String, userId: String, authName: String?) {
    queueOwnName(householdId, userId, authName)
  }

  override suspend fun queueOwnName(
      householdId: String,
      userId: String,
      authName: String?
  ): CompletableDeferred<Result<Unit>> {
    queueAttempts++
    try {
      queueGate?.await()
    } catch (cancelled: CancellationException) {
      cancelledQueues++
      throw cancelled
    }
    publicationFailure?.let { throw it }
    published += authName
    return confirmation
  }
}

internal fun testMemberNameRepository(names: Map<String, String> = emptyMap()) =
    TestMemberNames(names)

internal fun testPseudoRepository() = FakePseudoRepository()
