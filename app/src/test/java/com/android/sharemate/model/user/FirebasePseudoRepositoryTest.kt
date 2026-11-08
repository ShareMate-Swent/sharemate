// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.user

import com.android.sharemate.model.FirestoreCollections
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.WriteBatch
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
  private var listener: EventListener<DocumentSnapshot>? = null
  private var removed = 0
  private val writes = mutableListOf<Pair<DocumentReference, Any>>()
  private val registration =
      mock(ListenerRegistration::class.java) {
        if (it.method.name == "remove") removed++
        null
      }

  private fun snapshot(value: Any?) =
      mock(DocumentSnapshot::class.java) {
        if (it.method.name == "get" && it.arguments.singleOrNull() == "pseudo") value else null
      }

  private val privateDocument =
      mock(DocumentReference::class.java) {
        if (it.method.name == "get") Tasks.forResult(snapshot(stored)) else null
      }
  private val sharedDocument =
      mock(DocumentReference::class.java) {
        if (it.method.name == "addSnapshotListener" && it.arguments.size == 1) {
          @Suppress("UNCHECKED_CAST")
          listener = it.arguments[0] as EventListener<DocumentSnapshot>
          registration
        } else null
      }
  private val privateCollection = mock(CollectionReference::class.java) { privateDocument }
  private val sharedCollection = mock(CollectionReference::class.java) { sharedDocument }
  private val batch =
      mock(WriteBatch::class.java) {
        when (it.method.name) {
          "set" -> {
            writes += (it.arguments[0] as DocumentReference) to it.arguments[1]
            it.mock
          }
          "commit" -> Tasks.forResult<Void>(null)
          else -> null
        }
      }
  private val firestore =
      mock(FirebaseFirestore::class.java) {
        when (it.method.name) {
          "collection" ->
              when (it.arguments[0]) {
                FirestoreCollections.USERS -> privateCollection
                FirestoreCollections.USER_PSEUDOS -> sharedCollection
                else -> null
              }
          "batch" -> batch
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
  fun savesTrimmedPseudoInBothDocuments() = runTest {
    repository.setPseudo("alice", " Alice ")
    assertEquals(
        listOf(
            privateDocument to mapOf("pseudo" to "Alice"),
            sharedDocument to mapOf("pseudo" to "Alice")),
        writes)
  }

  @Test
  fun rejectsBlankPseudoWithoutWriting() = runTest {
    val failure = runCatching { repository.setPseudo("alice", "  ") }.exceptionOrNull()
    assertTrue(failure is IllegalArgumentException)
    assertTrue(writes.isEmpty())
  }

  @Test
  fun observesUpdatesAndRemovesListenerOnCancellation() = runTest {
    val results = mutableListOf<Map<String, String>>()
    val job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observePseudos(setOf("alice")).collect { results += it }
        }
    listener!!.onEvent(snapshot("Alice"), null)
    listener!!.onEvent(snapshot("  "), null)
    listener!!.onEvent(snapshot("Bob"), null)
    assertEquals(
        listOf(emptyMap(), mapOf("alice" to "Alice"), emptyMap(), mapOf("alice" to "Bob")), results)
    job.cancel()
    job.join()
    assertEquals(1, removed)
  }

  @Test
  fun noOwnersRequiresNoListener() = runTest {
    assertEquals(emptyMap<String, String>(), repository.observePseudos(emptySet()).first())
    assertNull(listener)
  }
}
