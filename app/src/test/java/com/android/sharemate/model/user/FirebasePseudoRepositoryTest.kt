// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.user

import com.android.sharemate.model.FirestoreCollections
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class FirebasePseudoRepositoryTest {
  private var stored: Any? = null
  private val listeners = mutableMapOf<String, EventListener<DocumentSnapshot>>()
  private var removed = 0
  private val writes = mutableListOf<Triple<String, Any, Any>>()
  private val registration =
      mock(ListenerRegistration::class.java) {
        if (it.method.name == "remove") removed++
        null
      }

  private fun snapshot(value: Any?) =
      mock(DocumentSnapshot::class.java) {
        if (it.method.name == "get" && it.arguments.singleOrNull() == "displayName") value else null
      }

  private fun document(id: String) =
      mock(DocumentReference::class.java) {
        when (it.method.name) {
          "get" -> Tasks.forResult(snapshot(stored))
          "addSnapshotListener" -> {
            @Suppress("UNCHECKED_CAST")
            listeners[id] = it.arguments[0] as EventListener<DocumentSnapshot>
            registration
          }
          "set" -> {
            writes += Triple(id, it.arguments[0], it.arguments[1])
            Tasks.forResult<Void>(null)
          }
          else -> null
        }
      }

  private val collection =
      mock(CollectionReference::class.java) { document(it.arguments[0] as String) }
  private val firestore =
      mock(FirebaseFirestore::class.java) {
        when (it.method.name) {
          "collection" ->
              when (it.arguments[0]) {
                FirestoreCollections.PROFILES -> collection
                else -> null
              }
          else -> null
        }
      }
  private val repository = FirebasePseudoRepository(firestore)

  @Test
  fun readsOnlyValidNonblankPseudos() = runTest {
    stored = "  Alice  "
    assertEquals("Alice", repository.getPseudo("alice"))
    stored = "  "
    assertNull(repository.getPseudo("alice"))
    stored = 42
    assertNull(repository.getPseudo("alice"))
  }

  @Test
  fun savesTrimmedPseudoInPublicProfileWithoutReplacingOtherFields() = runTest {
    repository.setPseudo("alice", " Alice ")
    assertEquals(
        listOf(Triple("alice", mapOf("displayName" to "Alice"), SetOptions.merge())), writes)
  }

  @Test
  fun rejectsBlankPseudoWithoutWriting() = runTest {
    val failure = runCatching { repository.setPseudo("alice", "  ") }.exceptionOrNull()
    assertTrue(failure is IllegalArgumentException)
    assertTrue(writes.isEmpty())
  }

  @Test
  fun validatesPseudoBoundariesBeforeWriting() = runTest {
    for (value in listOf("", "\u200B\u0301", "x".repeat(MAX_PSEUDO_LENGTH + 1))) {
      assertTrue(
          runCatching { repository.setPseudo("alice", value) }.exceptionOrNull()
              is IllegalArgumentException)
    }
    assertTrue(writes.isEmpty())

    repository.setPseudo("alice", "\u200Bx\u00A0")
    repository.setPseudo("alice", "😀".repeat(MAX_PSEUDO_LENGTH))
    assertEquals(mapOf("displayName" to "x"), writes[0].second)
    assertEquals(mapOf("displayName" to "😀".repeat(MAX_PSEUDO_LENGTH)), writes[1].second)
  }

  @Test
  fun observesUpdatesAndRemovesListenerOnCancellation() = runTest {
    val results = mutableListOf<Map<String, String>>()
    val job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observePseudos(setOf("alice")).collect { results += it }
        }
    assertTrue(results.isEmpty())
    listeners.getValue("alice").onEvent(snapshot("Alice"), null)
    listeners.getValue("alice").onEvent(snapshot("  "), null)
    listeners.getValue("alice").onEvent(snapshot("Bob"), null)
    assertEquals(listOf(mapOf("alice" to "Alice"), emptyMap(), mapOf("alice" to "Bob")), results)
    job.cancel()
    job.join()
    assertEquals(1, removed)
  }

  @Test
  fun waitsForEachOwnerBeforeFirstEmission() = runTest {
    val results = mutableListOf<Map<String, String>>()
    val job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observePseudos(setOf("alice", "bob")).collect { results += it }
        }
    listeners.getValue("alice").onEvent(snapshot("Alice"), null)
    assertTrue(results.isEmpty())
    listeners.getValue("bob").onEvent(snapshot(null), null)
    assertEquals(listOf(mapOf("alice" to "Alice")), results)
    job.cancel()
    job.join()
    assertEquals(2, removed)
  }

  @Test
  fun listenerFailureEmitsFallbackAndLaterUpdatesRecover() = runTest {
    val results = mutableListOf<Map<String, String>>()
    val job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observePseudos(setOf("alice")).collect { results += it }
        }
    listeners
        .getValue("alice")
        .onEvent(
            null,
            FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED))
    listeners.getValue("alice").onEvent(snapshot("Alice"), null)
    assertEquals(listOf(emptyMap(), mapOf("alice" to "Alice")), results)
    job.cancel()
  }

  @Test
  fun noOwnersRequiresNoListener() = runTest {
    assertEquals(emptyMap<String, String>(), repository.observePseudos(emptySet()).first())
    assertTrue(listeners.isEmpty())
  }
}
