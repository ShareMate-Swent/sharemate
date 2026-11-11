// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.item.InventorySubmissionFields as Fields
import com.android.sharemate.model.staging.StagedItem
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.firebase.firestore.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class FirebaseInventorySubmissionTest {
  @Test
  fun draftCannotBeEnqueuedBeforeFinalValidation() {
    val draft = InventorySubmission(ID, "owner", "home", submission.items, draft = true)
    val result = FirebaseItemRepository(firestore).addItems(draft)
    assertTrue(result is ItemWrite.Rejected)
    verifyNoInteractions(firestore)
  }

  private val firestore = mock(FirebaseFirestore::class.java)
  private val items = mock(CollectionReference::class.java)
  private val users = mock(CollectionReference::class.java)
  private val user = mock(DocumentReference::class.java)
  private val markers = mock(CollectionReference::class.java)
  private val marker = mock(DocumentReference::class.java)
  private val batch = mock(WriteBatch::class.java)
  private val task = TaskCompletionSource<Void>()
  private val submission =
      InventorySubmission(
          ID,
          "owner",
          "home",
          listOf(StagedItem("milk", "Oat milk", 2, 480), StagedItem("bread", "Bread", 1, null)))

  private fun repository(): FirebaseItemRepository {
    `when`(firestore.collection(FirestoreCollections.ITEMS)).thenReturn(items)
    `when`(firestore.collection(FirestoreCollections.USERS)).thenReturn(users)
    `when`(users.document(submission.ownerId)).thenReturn(user)
    `when`(user.collection(Fields.COLLECTION)).thenReturn(markers)
    `when`(markers.document(ID)).thenReturn(marker)
    `when`(firestore.batch()).thenReturn(batch)
    `when`(batch.commit()).thenReturn(task.task)
    return FirebaseItemRepository(firestore)
  }

  @Test
  fun batchKeepsCorrectedRowsAndPricesWithoutMultiplyingThemAndAwaitsServer() = runTest {
    val repository = repository()
    val refs =
        submission.items.indices.map { index ->
          mock(DocumentReference::class.java).also {
            `when`(items.document(submission.itemId(index))).thenReturn(it)
          }
        }
    val write = repository.addItems(submission) as ItemWrite.Queued
    submission.items.forEachIndexed { index, row ->
      verify(batch)
          .set(
              refs[index],
              Item(
                  id = submission.itemId(index),
                  name = row.name,
                  ownerId = submission.ownerId,
                  householdId = submission.householdId,
                  quantity = row.quantity,
                  sharing = ItemSharing.SHARED,
                  status = ItemStatus.ACTIVE))
    }
    val payload = ArgumentCaptor.forClass(Map::class.java)
    verify(batch).set(eq(marker), payload.capture())
    assertEquals(submission.ownerId, payload.value[Fields.OWNER])
    assertEquals(submission.householdId, payload.value[Fields.HOUSEHOLD])
    @Suppress("UNCHECKED_CAST") val rows = payload.value[Fields.ROWS] as List<Map<String, Any?>>
    assertEquals(480L, rows.first()[Fields.PRICE])
    assertNull(rows.last()[Fields.PRICE])
    assertEquals(2L, rows.first()[Fields.QUANTITY])
    verify(batch, times(1)).commit()
    assertFalse(write.confirmation.isCompleted)
    task.setResult(null)
    assertTrue(write.confirmation.await().isSuccess)
  }

  @Test
  fun serverRejectionIsNotReportedAsSuccess() = runTest {
    val repository = repository()
    submission.items.indices.forEach {
      `when`(items.document(submission.itemId(it))).thenReturn(mock(DocumentReference::class.java))
    }
    val write = repository.addItems(submission) as ItemWrite.Queued
    val denied =
        FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
    task.setException(denied)
    assertSame(denied, write.confirmation.await().exceptionOrNull())
  }

  @Test
  fun submissionValidationRejectsUnsupportedPayloadsBeforeRepositoryWrites() {
    val row = StagedItem("row", "Milk", 2, 480)
    for (count in listOf(InventorySubmission.MAX_ITEMS - 1, InventorySubmission.MAX_ITEMS)) {
      assertEquals(
          count,
          InventorySubmission(ID, "owner", "home", List(count) { row.copy(id = "$it") }).items.size)
    }
    for (rows in
        listOf(
            emptyList(),
            listOf(row, row),
            List(InventorySubmission.MAX_ITEMS + 1) { row.copy(id = "$it") },
            listOf(row.copy(name = "a".repeat(InventorySubmission.MAX_NAME_LENGTH + 1))),
            listOf(row.copy(id = "a".repeat(InventorySubmission.MAX_NAME_LENGTH + 1))))) {
      assertThrows(IllegalArgumentException::class.java) {
        InventorySubmission(ID, "owner", "home", rows)
      }
    }
    for (identity in listOf("", " ", "a/b")) {
      assertThrows(IllegalArgumentException::class.java) {
        InventorySubmission(ID, identity, "home", listOf(row))
      }
      assertThrows(IllegalArgumentException::class.java) {
        InventorySubmission(ID, "owner", identity, listOf(row))
      }
    }
    assertThrows(IllegalArgumentException::class.java) {
      InventorySubmission("not-a-uuid", "owner", "home", listOf(row))
    }
    assertThrows(IllegalArgumentException::class.java) {
      InventorySubmission(ID.uppercase(), "owner", "home", listOf(row))
    }
    verifyNoInteractions(firestore)
  }

  @Test
  fun retainedSubmissionCannotBeChangedThroughCallerOwnedLists() {
    val input = submission.items.toMutableList()
    val retained = InventorySubmission(ID, "owner", "home", input)
    input.clear()
    assertEquals(submission.items, retained.items)
    assertThrows(UnsupportedOperationException::class.java) {
      (retained.items as MutableList<StagedItem>).clear()
    }
    assertEquals(submission.items, retained.items)
  }

  @Test
  fun submissionEqualityIncludesIdentityScopeCorrectionsAndDraftState() {
    fun copy(
        id: String = ID,
        owner: String = "owner",
        home: String = "home",
        rows: List<StagedItem> = submission.items,
        draft: Boolean = false,
    ) = InventorySubmission(id, owner, home, rows, draft)
    val restored = copy()
    assertEquals(submission, restored)
    assertEquals(submission.hashCode(), restored.hashCode())
    assertEquals(1, hashSetOf(submission, restored).size)
    val different =
        listOf(
            copy(id = "22345678-1234-1234-1234-123456789abc"),
            copy(owner = "another"),
            copy(home = "another"),
            copy(rows = submission.items.map { it.copy(quantity = it.quantity + 1) }),
            copy(draft = true))
    different.forEach { assertNotEquals(submission, it) }
    assertFalse(submission.equals(null))
    assertFalse(submission.equals(ID))
    assertEquals(different.size + 1, (different + submission).toSet().size)
  }

  @Suppress("UNCHECKED_CAST")
  @Test
  fun observationRequiresServerMetadataAndRemovesItsListener() = runTest {
    val repository = repository()
    val listener =
        ArgumentCaptor.forClass(EventListener::class.java)
            as ArgumentCaptor<EventListener<DocumentSnapshot>>
    val registration = mock(ListenerRegistration::class.java)
    `when`(marker.addSnapshotListener(eq(MetadataChanges.INCLUDE), listener.capture()))
        .thenReturn(registration)
    val values = mutableListOf<InventorySubmissionStatus>()
    val job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observeSubmission(submission).collect(values::add)
        }
    runCurrent()
    fun emit(cached: Boolean, pending: Boolean, exists: Boolean) {
      val snapshot = mock(DocumentSnapshot::class.java)
      val metadata = mock(SnapshotMetadata::class.java)
      `when`(snapshot.metadata).thenReturn(metadata)
      `when`(metadata.isFromCache).thenReturn(cached)
      `when`(metadata.hasPendingWrites()).thenReturn(pending)
      `when`(snapshot.exists()).thenReturn(exists)
      `when`(snapshot.data)
          .thenReturn(
              mapOf(
                  Fields.ID to submission.id,
                  Fields.OWNER to submission.ownerId,
                  Fields.HOUSEHOLD to submission.householdId,
                  Fields.ROWS to
                      submission.items.map {
                        mapOf(
                            Fields.ID to it.id,
                            Fields.NAME to it.name,
                            Fields.QUANTITY to it.quantity.toLong(),
                            Fields.PRICE to it.priceCents)
                      }))
      listener.value.onEvent(snapshot, null)
      runCurrent()
    }
    emit(true, false, false)
    emit(true, false, true)
    emit(false, true, true)
    assertEquals(listOf(InventorySubmissionStatus.PENDING), values)
    emit(false, false, true)
    emit(false, false, false)
    assertEquals(
        listOf(
            InventorySubmissionStatus.PENDING,
            InventorySubmissionStatus.CONFIRMED,
            InventorySubmissionStatus.MISSING),
        values)
    job.cancel()
    runCurrent()
    verify(registration).remove()
  }

  @Suppress("UNCHECKED_CAST")
  @Test
  fun observationPropagatesPermissionDenialAndCleansUp() = runTest {
    val repository = repository()
    val listener =
        ArgumentCaptor.forClass(EventListener::class.java)
            as ArgumentCaptor<EventListener<DocumentSnapshot>>
    val registration = mock(ListenerRegistration::class.java)
    `when`(marker.addSnapshotListener(eq(MetadataChanges.INCLUDE), listener.capture()))
        .thenReturn(registration)
    var failure: Throwable? = null
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      try {
        repository.observeSubmission(submission).collect {}
      } catch (error: Exception) {
        failure = error
      }
    }
    runCurrent()
    val denied =
        FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
    listener.value.onEvent(null, denied)
    runCurrent()
    assertSame(denied, failure)
    verify(registration).remove()
  }

  private companion object {
    const val ID = "12345678-1234-1234-1234-123456789abc"
  }
}
