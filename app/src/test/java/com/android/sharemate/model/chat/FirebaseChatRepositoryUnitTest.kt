// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.chat

import com.android.sharemate.model.FirestoreCollections
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class FirebaseChatRepositoryUnitTest {
  private lateinit var collection: CollectionReference
  private lateinit var document: DocumentReference
  private lateinit var repository: FirebaseChatRepository

  @Before
  fun setUp() {
    val firestore = mock(FirebaseFirestore::class.java)
    val household = mock(DocumentReference::class.java)
    collection = mock(CollectionReference::class.java)
    document = mock(DocumentReference::class.java)
    val households = mock(CollectionReference::class.java)
    `when`(households.document("h1")).thenReturn(household)
    `when`(firestore.collection(FirestoreCollections.HOUSEHOLDS)).thenReturn(households)
    `when`(household.collection(FirestoreCollections.MESSAGES)).thenReturn(collection)
    `when`(collection.document()).thenReturn(document)
    `when`(document.id).thenReturn("m1")
    repository = FirebaseChatRepository(firestore)
  }

  @Test
  fun observeMessages_blankHousehold_emitsEmpty() =
      runBlocking<Unit> { assertTrue(repository.observeMessages(" ").first().isEmpty()) }

  @Suppress("UNCHECKED_CAST")
  @Test
  fun observeMessages_emitsOrderedParsedMessages_skippingInvalid() =
      runBlocking<Unit> {
        val query = mock(Query::class.java)
        `when`(collection.orderBy("timestamp", Query.Direction.ASCENDING)).thenReturn(query)
        val captor =
            ArgumentCaptor.forClass(EventListener::class.java)
                as ArgumentCaptor<EventListener<QuerySnapshot>>
        `when`(query.addSnapshotListener(captor.capture()))
            .thenReturn(mock(ListenerRegistration::class.java))

        val emitted = mutableListOf<List<Message>>()
        val job =
            launch(Dispatchers.Unconfined) {
              repository.observeMessages("h1").collect { emitted.add(it) }
            }

        val date = Date(1000)
        val valid = mock(DocumentSnapshot::class.java)
        `when`(valid.id).thenReturn("a")
        `when`(valid.getString("senderId")).thenReturn("u1")
        `when`(valid.getString("senderName")).thenReturn("Ann")
        `when`(valid.getString("text")).thenReturn("hi")
        `when`(valid.getTimestamp("timestamp")).thenReturn(Timestamp(date))
        val invalid = mock(DocumentSnapshot::class.java)
        val snapshot = mock(QuerySnapshot::class.java)
        `when`(snapshot.documents).thenReturn(listOf(valid, invalid))

        captor.value.onEvent(snapshot, null)

        assertEquals(listOf(listOf(Message("a", "u1", "Ann", "hi", date))), emitted)
        job.cancel()
      }

  @Test
  fun sendMessage_invalidInput_returnsNullWithoutWriting() =
      runBlocking<Unit> {
        assertNull(repository.sendMessage("h1", "u1", "Ann", "   "))
        assertNull(repository.sendMessage("", "u1", "Ann", "hi"))
        assertNull(repository.sendMessage("h1", "", "Ann", "hi"))
        verify(document, never()).set(any())
      }

  @Test
  fun sendMessage_writesTrimmedMessageWithDocumentId() =
      runBlocking<Unit> {
        `when`(document.set(any())).thenReturn(Tasks.forResult(null))
        assertEquals("m1", repository.sendMessage("h1", "u1", "Ann", "  hi  "))
        val captor = ArgumentCaptor.forClass(Message::class.java)
        verify(document).set(captor.capture())
        assertEquals("m1", captor.value.id)
        assertEquals("hi", captor.value.text)
        assertEquals("u1", captor.value.senderId)
        assertNotNull(captor.value.timestamp)
      }

  @Test
  fun sendMessage_returnsNullOnFailure() =
      runBlocking<Unit> {
        `when`(document.set(any())).thenReturn(Tasks.forException(RuntimeException("x")))
        assertNull(repository.sendMessage("h1", "u1", "Ann", "hi"))
      }

  @Test
  fun sendActivity_invalidInput_returnsNullWithoutWriting() =
      runBlocking<Unit> {
        assertNull(repository.sendActivity("", "u1", "Ann", MessageType.EATEN, "Milk"))
        assertNull(repository.sendActivity("h1", "", "Ann", MessageType.EATEN, "Milk"))
        assertNull(repository.sendActivity("h1", "u1", "Ann", MessageType.TEXT, "Milk"))
        assertNull(repository.sendActivity("h1", "u1", "Ann", MessageType.THROWN, " "))
        verify(document, never()).set(any())
      }

  @Test
  fun sendActivity_writesActivityMessage() =
      runBlocking<Unit> {
        `when`(document.set(any())).thenReturn(Tasks.forResult(null))
        assertEquals(
            "m1",
            repository.sendActivity("h1", "u1", "Ann", MessageType.THROWN, " Milk ", "http://i"))
        val captor = ArgumentCaptor.forClass(Message::class.java)
        verify(document).set(captor.capture())
        assertEquals(MessageType.THROWN, captor.value.type)
        assertEquals("Milk", captor.value.itemName)
        assertEquals("http://i", captor.value.itemImageUrl)
        assertEquals("", captor.value.text)
        assertNotNull(captor.value.timestamp)
      }

  @Test
  fun sendActivity_returnsNullOnFailure() =
      runBlocking<Unit> {
        `when`(document.set(any())).thenReturn(Tasks.forException(RuntimeException("x")))
        assertNull(repository.sendActivity("h1", "u1", "Ann", MessageType.EATEN, "Milk"))
      }

  @Test
  fun sendActivity_rethrowsCancellation() {
    `when`(document.set(any())).thenThrow(CancellationException("c"))
    try {
      runBlocking { repository.sendActivity("h1", "u1", "Ann", MessageType.EATEN, "Milk") }
      org.junit.Assert.fail("expected cancellation")
    } catch (_: CancellationException) {}
  }

  @Test
  fun toMessageOrNull_parsesActivityAndDefaultsUnknownTypeToText() {
    val activity = mock(DocumentSnapshot::class.java)
    `when`(activity.id).thenReturn("a")
    `when`(activity.getString("senderId")).thenReturn("u1")
    `when`(activity.getString("type")).thenReturn("EATEN")
    `when`(activity.getString("itemName")).thenReturn("Milk")
    `when`(activity.getString("itemImageUrl")).thenReturn("http://i")
    assertEquals(
        Message("a", "u1", "", "", null, MessageType.EATEN, "Milk", "http://i"),
        activity.toMessageOrNull())

    val noItem = mock(DocumentSnapshot::class.java)
    `when`(noItem.getString("senderId")).thenReturn("u1")
    `when`(noItem.getString("type")).thenReturn("THROWN")
    assertNull(noItem.toMessageOrNull())

    val unknown = mock(DocumentSnapshot::class.java)
    `when`(unknown.id).thenReturn("b")
    `when`(unknown.getString("senderId")).thenReturn("u1")
    `when`(unknown.getString("type")).thenReturn("???")
    `when`(unknown.getString("text")).thenReturn("hi")
    assertEquals(Message("b", "u1", "", "hi", null), unknown.toMessageOrNull())
  }

  @Suppress("UNCHECKED_CAST")
  private fun captureListener():
      Pair<ArgumentCaptor<EventListener<QuerySnapshot>>, ListenerRegistration> {
    val query = mock(Query::class.java)
    `when`(collection.orderBy("timestamp", Query.Direction.ASCENDING)).thenReturn(query)
    val captor =
        ArgumentCaptor.forClass(EventListener::class.java)
            as ArgumentCaptor<EventListener<QuerySnapshot>>
    val registration = mock(ListenerRegistration::class.java)
    `when`(query.addSnapshotListener(captor.capture())).thenReturn(registration)
    return captor to registration
  }

  @Test
  fun observeMessages_closesFlowWithListenerError() =
      runBlocking<Unit> {
        val (captor, _) = captureListener()
        var failure: Throwable? = null
        val job =
            launch(Dispatchers.Unconfined) {
              try {
                repository.observeMessages("h1").collect {}
              } catch (e: Exception) {
                failure = e
              }
            }
        val error = mock(FirebaseFirestoreException::class.java)
        captor.value.onEvent(null, error)
        assertEquals(error, failure)
        job.cancel()
      }

  @Test
  fun observeMessages_nullSnapshotEmitsEmpty_andCancelRemovesListener() =
      runBlocking<Unit> {
        val (captor, registration) = captureListener()
        val emitted = mutableListOf<List<Message>>()
        val job =
            launch(Dispatchers.Unconfined) {
              repository.observeMessages("h1").collect { emitted.add(it) }
            }
        captor.value.onEvent(null, null)
        assertEquals(listOf(emptyList<Message>()), emitted)
        job.cancel()
        verify(registration).remove()
      }

  @Test
  fun sendMessage_rethrowsCancellation() {
    `when`(document.set(any())).thenThrow(CancellationException("cancelled"))
    try {
      runBlocking { repository.sendMessage("h1", "u1", "Ann", "hi") }
      org.junit.Assert.fail("expected cancellation")
    } catch (_: CancellationException) {}
  }

  @Test
  fun toMessageOrNull_handlesMissingFields() {
    assertNull(mock(DocumentSnapshot::class.java).toMessageOrNull())
    val noText = mock(DocumentSnapshot::class.java)
    `when`(noText.getString("senderId")).thenReturn("u1")
    assertNull(noText.toMessageOrNull())
    val minimal = mock(DocumentSnapshot::class.java)
    `when`(minimal.id).thenReturn("x")
    `when`(minimal.getString("senderId")).thenReturn("u1")
    `when`(minimal.getString("text")).thenReturn("hi")
    assertEquals(Message("x", "u1", "", "hi", null), minimal.toMessageOrNull())
  }

  @Test
  fun toMessageOrNull_skipsWronglyTypedFields() {
    val doc = mock(DocumentSnapshot::class.java)
    `when`(doc.getString("senderId")).thenReturn("u1")
    `when`(doc.getString("text")).thenReturn("hi")
    `when`(doc.getString("senderName")).thenThrow(RuntimeException("not a string"))
    assertNull(doc.toMessageOrNull())

    val badTimestamp = mock(DocumentSnapshot::class.java)
    `when`(badTimestamp.getString("senderId")).thenReturn("u1")
    `when`(badTimestamp.getString("text")).thenReturn("hi")
    `when`(badTimestamp.getTimestamp("timestamp")).thenThrow(RuntimeException("bad"))
    assertNull(badTimestamp.toMessageOrNull())
  }
}
