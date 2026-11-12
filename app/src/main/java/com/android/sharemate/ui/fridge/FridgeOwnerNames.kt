// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.user.MemberNameRepository
import com.android.sharemate.model.user.PseudoRepository
import com.android.sharemate.model.user.ownerDisplayName
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal enum class OwnerNameFailure {
  DIRECTORY,
  PROFILE,
  PUBLICATION
}

/** Name-only state. Peer names always follow the repository's server-confirmed access contract. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class FridgeOwnerNames(
    directory: MemberNameRepository,
    profiles: PseudoRepository,
    householdId: String,
    userId: String,
    fridgeType: FridgeType,
    authName: String?,
    scope: CoroutineScope,
    reportFailure: (OwnerNameFailure, Throwable) -> Unit,
) {
  private val fallback = ownerDisplayName(authName)
  private val mutableNames = MutableStateFlow(fallback?.let { mapOf(userId to it) }.orEmpty())
  val names = mutableNames.asStateFlow()
  private val handlers = ConcurrentHashMap<Any, DisposableHandle>()

  init {
    require(userId.isNotBlank() && householdId.isNotBlank())
    scope.launch {
      fun acknowledge(confirmation: Deferred<Result<Unit>>) {
        val token = Any()
        handlers[token] = DisposableHandle {}
        val handler =
            confirmation.invokeOnCompletion { cause ->
              handlers.remove(token)?.dispose()
              val failure = cause ?: confirmation.getCompleted().exceptionOrNull()
              if (isActive && failure != null && failure !is CancellationException) {
                reportFailure(OwnerNameFailure.PUBLICATION, failure)
              }
            }
        if (handlers.replace(token, handler) == null) handler.dispose()
      }
      val ownName =
          flow { emitAll(profiles.observePseudos(setOf(userId))) }
              .map { ownerDisplayName(it[userId]) ?: fallback }
              .catch {
                if (it is CancellationException) throw it
                reportFailure(OwnerNameFailure.PROFILE, it)
                emit(fallback)
              }
              .distinctUntilChanged()
              .onEach { name ->
                if (fridgeType == FridgeType.SHARED) {
                  try {
                    acknowledge(directory.queueOwnName(householdId, userId, name))
                  } catch (cancelled: CancellationException) {
                    throw cancelled
                  } catch (failure: Exception) {
                    reportFailure(OwnerNameFailure.PUBLICATION, failure)
                  }
                }
              }
              .onStart { emit(fallback) }
      val peers =
          if (fridgeType == FridgeType.SHARED) {
            flow { emitAll(directory.observeNames(householdId, userId)) }
                .catch {
                  if (it is CancellationException) throw it
                  reportFailure(OwnerNameFailure.DIRECTORY, it)
                  emit(emptyMap())
                }
                .onStart { emit(emptyMap()) }
          } else flowOf(emptyMap())
      try {
        combine(peers, ownName) { peers, own ->
              peers
                  .mapNotNull { (id, value) -> ownerDisplayName(value)?.let { id to it } }
                  .toMap() + own?.let { mapOf(userId to it) }.orEmpty()
            }
            .collect { mutableNames.value = it }
        // Finite sources must not detach pending acknowledgements before scope teardown.
        awaitCancellation()
      } finally {
        handlers.values.forEach { it.dispose() }
        handlers.clear()
      }
    }
  }
}
