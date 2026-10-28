// Co-authored-by: OpenAI Codex
package com.android.sharemate.model.household

import com.android.sharemate.model.FirestoreCollections
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.WriteBatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HouseholdInviteCodeCollisionTest {
  private val firestore = mock(FirebaseFirestore::class.java)
  private val codes = mock(CollectionReference::class.java)
  private val households = mock(CollectionReference::class.java)
  private val users = mock(CollectionReference::class.java)
  private val codeReference = mock(DocumentReference::class.java)
  private val householdReference = mock(DocumentReference::class.java)
  private val userReference = mock(DocumentReference::class.java)
  private val batch = mock(WriteBatch::class.java)
  private val generator = mock(InviteCodeGenerator::class.java)
  private val missing = mock(DocumentSnapshot::class.java)
  private val repository = HouseholdRepositoryFirestore(firestore, generator)

  @Before
  fun setUp() {
    `when`(firestore.collection(FirestoreCollections.INVITE_CODES)).thenReturn(codes)
    `when`(firestore.collection(FirestoreCollections.HOUSEHOLDS)).thenReturn(households)
    `when`(firestore.collection(FirestoreCollections.USERS)).thenReturn(users)
    `when`(codes.document(anyString())).thenReturn(codeReference)
    `when`(households.document()).thenReturn(householdReference)
    `when`(householdReference.id).thenReturn("new-household")
    `when`(users.document("creator")).thenReturn(userReference)
    `when`(firestore.batch()).thenReturn(batch)
    `when`(batch.set(eq(householdReference), any(Household::class.java))).thenReturn(batch)
    `when`(batch.set(codeReference, mapOf("householdId" to "new-household"))).thenReturn(batch)
    `when`(batch.set(userReference, mapOf("householdId" to "new-household"), SetOptions.merge()))
        .thenReturn(batch)
    `when`(batch.commit()).thenReturn(Tasks.forResult(null))
    `when`(generator.generate()).thenReturn("TAKEN1", "FREE22")
  }

  @Test
  fun deniedReadRetriesAndStoresOnlyTheNextCode() =
      runBlocking<Unit> {
        `when`(codeReference.get())
            .thenReturn(Tasks.forException(denied()), Tasks.forResult(missing))

        val created = repository.createHousehold("Home", "creator")

        assertEquals("FREE22", created.inviteCode)
        assertEquals(listOf("creator"), created.memberIds)
        verify(generator, times(2)).generate()
        verify(batch).set(householdReference, created)
        verify(batch, times(1)).commit()
      }

  @Test
  fun readableTakenCodeStillRetries() =
      runBlocking<Unit> {
        val taken = mock(DocumentSnapshot::class.java)
        `when`(taken.exists()).thenReturn(true)
        `when`(codeReference.get()).thenReturn(Tasks.forResult(taken), Tasks.forResult(missing))

        assertEquals("FREE22", repository.createHousehold("Home", "creator").inviteCode)
        verify(generator, times(2)).generate()
        verify(batch, times(1)).commit()
      }

  @Test
  fun tenDeniedReadsExhaustAttemptsWithoutWriting() {
    `when`(codeReference.get()).thenReturn(Tasks.forException(denied()))

    assertThrows(IllegalStateException::class.java) {
      runBlocking { repository.createHousehold("Home", "creator") }
    }

    verify(generator, times(10)).generate()
    verify(codeReference, times(10)).get()
    verify(firestore, never()).batch()
    verifyNoInteractions(households, users, batch)
  }

  @Test
  fun unrelatedReadFailuresPropagateWithoutRetryOrWrites() {
    val failures =
        listOf(
            FirebaseFirestoreException("offline", FirebaseFirestoreException.Code.UNAVAILABLE),
            FirebaseFirestoreException(
                "signed out", FirebaseFirestoreException.Code.UNAUTHENTICATED),
            IllegalStateException("unexpected response"))
    failures.forEach { failure ->
      clearInvocations(generator, codeReference, firestore)
      `when`(codeReference.get()).thenReturn(Tasks.forException(failure))

      val thrown =
          assertThrows(Exception::class.java) {
            runBlocking { repository.createHousehold("Home", "creator") }
          }

      assertSame(failure, thrown)
      verify(generator, times(1)).generate()
      verify(codeReference, times(1)).get()
      verify(firestore, never()).batch()
    }
  }

  @Test
  fun deniedCommitPropagatesWithoutRetry() {
    val failure = denied()
    `when`(codeReference.get()).thenReturn(Tasks.forResult(missing))
    `when`(batch.commit()).thenReturn(Tasks.forException(failure))

    val thrown =
        assertThrows(FirebaseFirestoreException::class.java) {
          runBlocking { repository.createHousehold("Home", "creator") }
        }

    assertSame(failure, thrown)
    verify(generator, times(1)).generate()
    verify(batch, times(1)).commit()
  }

  @Test
  fun cancelledReadPropagatesWithoutRetryOrWrites() {
    `when`(codeReference.get()).thenReturn(Tasks.forCanceled())

    assertThrows(CancellationException::class.java) {
      runBlocking { repository.createHousehold("Home", "creator") }
    }

    verify(generator, times(1)).generate()
    verify(firestore, never()).batch()
  }

  private fun denied() =
      FirebaseFirestoreException("hidden code", FirebaseFirestoreException.Code.PERMISSION_DENIED)
}
