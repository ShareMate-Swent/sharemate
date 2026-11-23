// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.recipes

import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.item.FirebaseItemRepository
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.firebase.firestore.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*

/** Exercises recipe requests across the repository boundary with cached and server data. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeInventoryFreshnessTest {
  @Test
  fun cachedInventoryCannotCompleteRecipeRequestBeforeServerRead() = runTest {
    for (cachedNames in listOf(emptyList(), listOf("Old milk"))) {
      for (serverNames in listOf(emptyList(), listOf("Fresh rice"))) {
        val firestore = mock(FirebaseFirestore::class.java)
        val collection = mock(CollectionReference::class.java)
        val query = mock(Query::class.java)
        val registration = mock(ListenerRegistration::class.java)
        val server = TaskCompletionSource<QuerySnapshot>()
        `when`(firestore.collection(FirestoreCollections.ITEMS)).thenReturn(collection)
        `when`(
                collection.where(
                    Filter.and(
                        Filter.equalTo("householdId", "home"),
                        Filter.or(
                            Filter.equalTo("sharing", "SHARED"),
                            Filter.equalTo("ownerId", "alice")))))
            .thenReturn(query)
        `when`(query.get(Source.SERVER)).thenReturn(server.task)
        `when`(query.addSnapshotListener(any<EventListener<QuerySnapshot>>())).thenAnswer {
          val listener = it.getArgument<EventListener<QuerySnapshot>>(0)
          listener.onEvent(snapshot(cachedNames), null)
          registration
        }
        val repository = FirebaseItemRepository(firestore)
        val live = backgroundScope.launch { repository.getFridgeItems("home", "alice").collect() }
        runCurrent()
        var serviceCalls = 0
        val request = async {
          RecipeIngredientProvider(repository).suggest("home", "alice") { names ->
            serviceCalls++
            names
          }
        }
        runCurrent()
        assertFalse("Cached inventory must not complete the request", request.isCompleted)
        assertEquals(0, serviceCalls)
        server.setResult(snapshot(serverNames))
        runCurrent()
        assertEquals(serverNames.map { it.lowercase() }, request.await())
        assertEquals(if (serverNames.isEmpty()) 0 else 1, serviceCalls)
        verify(query).get(Source.SERVER)
        live.cancel()
      }
    }
  }

  @Test
  fun serverFailureAfterCachedSnapshotPropagatesWithoutCallingService() = runTest {
    val firestore = mock(FirebaseFirestore::class.java)
    val collection = mock(CollectionReference::class.java)
    val query = mock(Query::class.java)
    val server = TaskCompletionSource<QuerySnapshot>()
    `when`(firestore.collection(FirestoreCollections.ITEMS)).thenReturn(collection)
    `when`(
            collection.where(
                Filter.and(
                    Filter.equalTo("householdId", "home"),
                    Filter.or(
                        Filter.equalTo("sharing", "SHARED"), Filter.equalTo("ownerId", "alice")))))
        .thenReturn(query)
    `when`(query.get(Source.SERVER)).thenReturn(server.task)
    `when`(query.addSnapshotListener(any<EventListener<QuerySnapshot>>())).thenAnswer {
      it.getArgument<EventListener<QuerySnapshot>>(0).onEvent(snapshot(listOf("Old milk")), null)
      mock(ListenerRegistration::class.java)
    }
    val repository = FirebaseItemRepository(firestore)
    backgroundScope.launch { repository.getFridgeItems("home", "alice").collect() }
    runCurrent()
    val failure = FirebaseFirestoreException("Offline", FirebaseFirestoreException.Code.UNAVAILABLE)
    val request = async {
      try {
        RecipeIngredientProvider(repository).suggest<String>("home", "alice") {
          fail("Service must not run after a failed read")
          emptyList()
        }
        null
      } catch (actual: FirebaseFirestoreException) {
        actual
      }
    }
    runCurrent()
    assertFalse(request.isCompleted)
    server.setException(failure)
    assertSame(failure, request.await())
  }

  @Test
  fun blankScopeDoesNotQueryFirestore() = runTest {
    val firestore = mock(FirebaseFirestore::class.java)
    val repository = FirebaseItemRepository(firestore)
    for ((home, user) in listOf("" to "alice", " " to "alice", "home" to "", "home" to " ")) {
      assertTrue(repository.getFridgeItemsFromServer(home, user).isEmpty())
    }
    verifyNoInteractions(firestore)
  }

  @Test
  fun cancellingServerReadStopsWaitingAndNeverCallsService() = runTest {
    val firestore = mock(FirebaseFirestore::class.java)
    val collection = mock(CollectionReference::class.java)
    val query = mock(Query::class.java)
    val server = TaskCompletionSource<QuerySnapshot>()
    `when`(firestore.collection(FirestoreCollections.ITEMS)).thenReturn(collection)
    `when`(collection.where(any(Filter::class.java))).thenReturn(query)
    `when`(query.get(Source.SERVER)).thenReturn(server.task)
    val request = launch {
      RecipeIngredientProvider(FirebaseItemRepository(firestore)).suggest<String>("home", "alice") {
        fail("A cancelled request must not call the service")
        emptyList()
      }
    }
    runCurrent()
    assertFalse(request.isCompleted)
    request.cancel()
    request.join()
    assertTrue(request.isCancelled)
    server.setResult(snapshot(listOf("Rice")))
    runCurrent()
    assertTrue(request.isCompleted)
  }

  private fun snapshot(names: List<String>): QuerySnapshot {
    val snapshot = mock(QuerySnapshot::class.java)
    val documents =
        names.map { name ->
          mock(DocumentSnapshot::class.java).also {
            `when`(it.id).thenReturn(name)
            `when`(it.getString("name")).thenReturn(name)
            `when`(it.getString("ownerId")).thenReturn("alice")
            `when`(it.getString("householdId")).thenReturn("home")
            `when`(it.get("quantity")).thenReturn(1L)
            `when`(it.get("status")).thenReturn("ACTIVE")
            `when`(it.get("sharing")).thenReturn("PERSONAL")
          }
        }
    `when`(snapshot.documents).thenReturn(documents)
    return snapshot
  }
}
