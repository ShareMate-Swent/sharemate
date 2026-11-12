// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.user.FakePseudoRepository
import com.android.sharemate.model.user.PseudoRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FridgeOwnerNamesTest {
  private val directory = TestMemberNames(mapOf("bob" to "Bob"))
  private val profiles = FakePseudoRepository(mapOf("alice" to "Alice"))
  private val failures = mutableListOf<Pair<OwnerNameFailure, Throwable>>()

  private fun TestScope.names(
      type: FridgeType = FridgeType.SHARED,
      source: PseudoRepository = profiles,
      scope: CoroutineScope = backgroundScope,
  ) =
      FridgeOwnerNames(directory, source, "home", "alice", type, "Auth name", scope) { kind, cause
        ->
        failures += kind to cause
      }

  @Test
  fun successfulPublicationUsesPseudoAndServerConfirmedPeers() = runTest {
    val names = names()
    runCurrent()
    assertEquals(mapOf("alice" to "Alice", "bob" to "Bob"), names.names.value)
    assertEquals(listOf("Alice"), directory.published)
    assertTrue(failures.isEmpty())
  }

  @Test
  fun offlineQueueDoesNotWaitAndReconnectionConfirmsWithoutAnotherPublication() = runTest {
    directory.confirmation = CompletableDeferred()
    val names = names()
    runCurrent()
    assertEquals("Alice", names.names.value["alice"])
    assertFalse(directory.confirmation.isCompleted)
    directory.confirmation.complete(Result.success(Unit))
    runCurrent()
    assertEquals(listOf("Alice"), directory.published)
    assertTrue(failures.isEmpty())
  }

  @Test
  fun serverRejectionAfterReconnectionIsReportedWithoutDiscardingAccessibleNames() = runTest {
    directory.confirmation = CompletableDeferred()
    val names = names()
    runCurrent()
    val denied = IllegalStateException("Permission denied")
    directory.confirmation.complete(Result.failure(denied))
    runCurrent()
    assertEquals(listOf(OwnerNameFailure.PUBLICATION to denied), failures)
    assertEquals("Bob", names.names.value["bob"])
  }

  @Test
  fun immediatePublicationFailureDoesNotStopDirectoryOrFutureProfileUpdates() = runTest {
    val denied = IllegalStateException("Rejected locally")
    directory.publicationFailure = denied
    val names = names()
    runCurrent()
    assertEquals(listOf(OwnerNameFailure.PUBLICATION to denied), failures)
    assertEquals("Bob", names.names.value["bob"])
    directory.publicationFailure = null
    profiles.setPseudo("alice", "New Alice")
    runCurrent()
    assertEquals(listOf("New Alice"), directory.published)
    assertEquals("New Alice", names.names.value["alice"])
  }

  @Test
  fun directoryFailureIsReportedIndependentlyOfSuccessfulPublication() = runTest {
    val denied = IllegalStateException("Directory denied")
    directory.failure = denied
    val names = names()
    runCurrent()
    assertEquals(listOf(OwnerNameFailure.DIRECTORY to denied), failures)
    assertEquals(mapOf("alice" to "Alice"), names.names.value)
    assertEquals(listOf("Alice"), directory.published)
  }

  @Test
  fun synchronousPseudoObservationFailureUsesAuthFallbackAndReportsProfileError() = runTest {
    val denied = IllegalStateException("Profile unavailable")
    val source =
        object : PseudoRepository {
          override fun observePseudos(userIds: Set<String>): Flow<Map<String, String>> {
            assertEquals(setOf("alice"), userIds)
            throw denied
          }

          override suspend fun getPseudo(userId: String): String? = error("unused")

          override suspend fun setPseudo(userId: String, pseudo: String) = error("unused")
        }
    val names = names(source = source)
    runCurrent()
    assertEquals(listOf(OwnerNameFailure.PROFILE to denied), failures)
    assertEquals(mapOf("alice" to "Auth name", "bob" to "Bob"), names.names.value)
    assertEquals(listOf("Auth name"), directory.published)
  }

  @Test
  fun privateFridgeUsesLiveOwnPseudoWithoutReadingOrPublishingDirectory() = runTest {
    val names = names(type = FridgeType.PRIVATE)
    runCurrent()
    assertEquals(mapOf("alice" to "Alice"), names.names.value)
    profiles.setPseudo("alice", "Private Alice")
    runCurrent()
    assertEquals(mapOf("alice" to "Private Alice"), names.names.value)
    assertEquals(0, directory.observations)
    assertTrue(directory.published.isEmpty())
  }

  @Test
  fun unconfirmedDirectoryClearsPeersAndReconnectRestoresConfirmedNames() = runTest {
    val names = names()
    runCurrent()
    directory.names.value = emptyMap()
    runCurrent()
    assertEquals(mapOf("alice" to "Alice"), names.names.value)
    directory.names.value = mapOf("bob" to "Robert")
    runCurrent()
    assertEquals("Robert", names.names.value["bob"])
  }

  @Test
  fun cancellationDetachesAllPendingAcknowledgementsWithoutCancellingSdkWrites() = runTest {
    val scope =
        CoroutineScope(
            backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
    val first = CompletableDeferred<Result<Unit>>()
    directory.confirmation = first
    val names = names(scope = scope)
    runCurrent()
    val second = CompletableDeferred<Result<Unit>>()
    directory.confirmation = second
    profiles.setPseudo("alice", "New Alice")
    runCurrent()
    scope.cancel()
    runCurrent()
    assertFalse(first.isCancelled)
    assertFalse(second.isCancelled)
    first.complete(Result.failure(IllegalStateException("late denial")))
    second.completeExceptionally(IllegalStateException("late exception"))
    profiles.setPseudo("alice", "After cancellation")
    runCurrent()
    assertTrue(failures.isEmpty())
    assertEquals("New Alice", names.names.value["alice"])
    assertEquals(listOf("Alice", "New Alice"), directory.published)
  }

  @Test
  fun cancelledAcknowledgementIsNotReportedAsServerRejection() = runTest {
    directory.confirmation = CompletableDeferred()
    names()
    runCurrent()
    directory.confirmation.cancel()
    runCurrent()
    assertTrue(failures.isEmpty())
  }

  @Test
  fun finiteSourcesStillReportLateServerRejection() = runTest {
    directory.finite = true
    directory.confirmation = CompletableDeferred()
    val finiteProfiles =
        object : PseudoRepository by profiles {
          override fun observePseudos(userIds: Set<String>) =
              profiles.observePseudos(userIds).take(1)
        }
    names(source = finiteProfiles)
    runCurrent()
    val denied = IllegalStateException("late rejection")
    directory.confirmation.complete(Result.failure(denied))
    runCurrent()
    assertEquals(listOf(OwnerNameFailure.PUBLICATION to denied), failures)
  }

  @Test
  fun pseudoCancellationDoesNotBecomeAnErrorOrPublication() = runTest {
    val cancelledProfiles =
        object : PseudoRepository by profiles {
          override fun observePseudos(userIds: Set<String>): Flow<Map<String, String>> = flow {
            throw CancellationException("source cancelled")
          }
        }
    names(source = cancelledProfiles)
    runCurrent()
    assertTrue(failures.isEmpty())
    assertTrue(directory.published.isEmpty())
  }
}
