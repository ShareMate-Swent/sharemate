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
import com.google.firebase.firestore.SnapshotMetadata
import com.google.firebase.firestore.Source
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
    `when`(missing.metadata).thenReturn(mock(SnapshotMetadata::class.java))
    `when`(firestore.collection(FirestoreCollections.INVITE_CODES)).thenReturn(codes)
    `when`(firestore.collection(FirestoreCollections.HOUSEHOLDS)).thenReturn(households)
    `when`(firestore.collection(FirestoreCollections.USERS)).thenReturn(users)
    `when`(codes.document(anyString())).thenReturn(codeReference)
    `when`(households.document(anyString())).thenReturn(householdReference)
    `when`(householdReference.get(Source.SERVER)).thenReturn(Tasks.forResult(missing))
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
        `when`(codeReference.get(Source.SERVER))
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
        `when`(codeReference.get(Source.SERVER))
            .thenReturn(Tasks.forResult(taken), Tasks.forResult(missing))

        assertEquals("FREE22", repository.createHousehold("Home", "creator").inviteCode)
        verify(generator, times(2)).generate()
        verify(batch, times(1)).commit()
      }

  @Test
  fun tenDeniedReadsExhaustAttemptsWithoutWriting() {
    `when`(codeReference.get(Source.SERVER)).thenReturn(Tasks.forException(denied()))

    assertThrows(IllegalStateException::class.java) {
      runBlocking { repository.createHousehold("Home", "creator") }
    }

    verify(generator, times(10)).generate()
    verify(codeReference, times(10)).get(Source.SERVER)
    verify(firestore, never()).batch()
    verifyNoInteractions(users, batch)
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
      `when`(codeReference.get(Source.SERVER)).thenReturn(Tasks.forException(failure))

      val thrown =
          assertThrows(Exception::class.java) {
            runBlocking { repository.createHousehold("Home", "creator") }
          }

      assertSame(failure, thrown)
      verify(generator, times(1)).generate()
      verify(codeReference, times(1)).get(Source.SERVER)
      verify(firestore, never()).batch()
    }
  }

  @Test
  fun deniedCommitPropagatesWithoutRetry() {
    val failure = denied()
    `when`(codeReference.get(Source.SERVER)).thenReturn(Tasks.forResult(missing))
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
    `when`(codeReference.get(Source.SERVER)).thenReturn(Tasks.forCanceled())

    assertThrows(CancellationException::class.java) {
      runBlocking { repository.createHousehold("Home", "creator") }
    }

    verify(generator, times(1)).generate()
    verify(firestore, never()).batch()
  }

  @Test
  fun claimedBetweenCheckAndCommitRetriesWithSameHouseholdId() =
      runBlocking<Unit> {
        val claimed = mock(DocumentSnapshot::class.java)
        `when`(claimed.metadata).thenReturn(mock(SnapshotMetadata::class.java))
        `when`(claimed.getString("householdId")).thenReturn("winner")
        `when`(codeReference.get(Source.SERVER))
            .thenReturn(
                Tasks.forResult(missing), Tasks.forResult(claimed), Tasks.forResult(missing))
        `when`(batch.commit()).thenReturn(Tasks.forException(denied()), Tasks.forResult(null))

        val created = repository.createHousehold("Home", "creator", operationId = "retry")

        assertEquals("FREE22", created.inviteCode)
        assertEquals("new-household", created.id)
        verify(households).document("creator:retry")
        verify(generator, times(2)).generate()
        verify(batch, times(2)).commit()
      }

  @Test
  fun tenConcurrentClaimsExhaustBudget() {
    val claimed = mock(DocumentSnapshot::class.java)
    `when`(claimed.metadata).thenReturn(mock(SnapshotMetadata::class.java))
    `when`(claimed.getString("householdId")).thenReturn("winner")
    var reads = 0
    `when`(codeReference.get(Source.SERVER)).thenAnswer {
      Tasks.forResult(if (reads++ % 2 == 0) missing else claimed)
    }
    `when`(batch.commit()).thenReturn(Tasks.forException(denied()))

    assertThrows(IllegalStateException::class.java) {
      runBlocking { repository.createHousehold("Home", "creator", operationId = "retry") }
    }

    verify(generator, times(10)).generate()
    verify(batch, times(10)).commit()
    verify(households).document("creator:retry")
  }

  @Test
  fun commitFailuresWithoutCollisionProofNeverRetry() {
    val failures =
        listOf(
            denied(),
            FirebaseFirestoreException("offline", FirebaseFirestoreException.Code.UNAVAILABLE),
            FirebaseFirestoreException("invalid", FirebaseFirestoreException.Code.INVALID_ARGUMENT),
            CancellationException("cancelled"))
    for (type in FridgeType.entries) {
      for (failure in failures) {
        clearInvocations(generator, batch)
        `when`(codeReference.get(Source.SERVER)).thenReturn(Tasks.forResult(missing))
        `when`(batch.commit()).thenReturn(Tasks.forException(failure))

        assertSame(
            failure,
            assertThrows(Exception::class.java) {
              runBlocking { repository.createHousehold("Home", "creator", type, "retry") }
            })
        verify(batch).commit()
        verify(generator, times(if (type == FridgeType.SHARED) 1 else 0)).generate()
      }
    }
  }

  @Test
  fun failedCollisionProbePreservesOriginalCommitFailure() {
    val failure = denied()
    `when`(codeReference.get(Source.SERVER))
        .thenReturn(Tasks.forResult(missing), Tasks.forException(IllegalStateException("offline")))
    `when`(batch.commit()).thenReturn(Tasks.forException(failure))

    assertSame(
        failure,
        assertThrows(Exception::class.java) {
          runBlocking { repository.createHousehold("Home", "creator") }
        })
    verify(batch).commit()
    verify(generator).generate()
  }

  @Test
  fun pendingHouseholdIsNotReportedAsConfirmedOrWrittenAgain() {
    val pending = mock(SnapshotMetadata::class.java)
    `when`(pending.hasPendingWrites()).thenReturn(true)
    `when`(missing.metadata).thenReturn(pending)

    assertThrows(IllegalStateException::class.java) {
      runBlocking { repository.createHousehold("Home", "creator") }
    }
    verify(firestore, never()).batch()
    verifyNoInteractions(generator)
  }

  @Test
  fun operationIdsRejectInvalidValuesBeforeFirestoreAccessAndAllowLengthBoundary() {
    for (operation in listOf("", "a/b", "a:b", "x".repeat(129))) {
      assertThrows(IllegalArgumentException::class.java) {
        runBlocking { repository.createHousehold("Home", "creator", operationId = operation) }
      }
    }
    verifyNoInteractions(firestore, generator)
    for (length in listOf(127, 128)) {
      val operation = "x".repeat(length)
      runBlocking { repository.createHousehold("Home", "creator", FridgeType.PRIVATE, operation) }
      verify(households).document("creator:$operation")
    }
  }

  @Test
  fun deniedCommitReconcilesAlreadySuccessfulOperationForBothTypes() {
    for (type in FridgeType.entries) {
      clearInvocations(batch, generator)
      `when`(householdReference.get(Source.SERVER)).thenReturn(Tasks.forResult(missing))
      `when`(codeReference.get(Source.SERVER)).thenReturn(Tasks.forResult(missing))
      val stored = mock(DocumentSnapshot::class.java)
      `when`(stored.metadata).thenReturn(mock(SnapshotMetadata::class.java))
      `when`(stored.exists()).thenReturn(true)
      var committed: Household? = null
      doAnswer {
            committed = it.getArgument(1)
            batch
          }
          .`when`(batch)
          .set(eq(householdReference), any(Household::class.java))
      doAnswer {
            `when`(stored.toObject(Household::class.java)).thenReturn(committed)
            `when`(householdReference.get(Source.SERVER)).thenReturn(Tasks.forResult(stored))
            Tasks.forException<Void>(denied())
          }
          .`when`(batch)
          .commit()
      val reconciled = runBlocking { repository.createHousehold("Home", "creator", type, "retry") }
      assertEquals(committed, reconciled)
      verify(batch).commit()
      verify(generator, times(if (type == FridgeType.SHARED) 1 else 0)).generate()
    }
  }

  @Test
  fun pendingOrOwnMappingCannotProveConcurrentClaim() {
    val claimed = mock(DocumentSnapshot::class.java)
    val metadata = mock(SnapshotMetadata::class.java)
    `when`(claimed.metadata).thenReturn(metadata)
    val failure = denied()
    for (pending in listOf(true, false)) {
      clearInvocations(batch, generator)
      `when`(metadata.hasPendingWrites()).thenReturn(pending)
      `when`(claimed.getString("householdId"))
          .thenReturn(if (pending) "winner" else "new-household")
      `when`(codeReference.get(Source.SERVER))
          .thenReturn(Tasks.forResult(missing), Tasks.forResult(claimed))
      `when`(batch.commit()).thenReturn(Tasks.forException(failure))
      assertSame(
          failure,
          assertThrows(Exception::class.java) {
            runBlocking { repository.createHousehold("Home", "creator") }
          })
      verify(batch).commit()
      verify(generator).generate()
    }
  }

  @Test
  fun retryAfterLostAcknowledgementReturnsOriginalWithoutWritingAgain() {
    for (type in FridgeType.entries) {
      for (failure in
          listOf(
              FirebaseFirestoreException("lost ack", FirebaseFirestoreException.Code.UNAVAILABLE),
              CancellationException("lost ack"))) {
        clearInvocations(batch, generator, households)
        `when`(householdReference.get(Source.SERVER)).thenReturn(Tasks.forResult(missing))
        `when`(codeReference.get(Source.SERVER)).thenReturn(Tasks.forResult(missing))
        val stored = mock(DocumentSnapshot::class.java)
        `when`(stored.metadata).thenReturn(mock(SnapshotMetadata::class.java))
        `when`(stored.exists()).thenReturn(true)
        `when`(batch.commit()).thenAnswer { Tasks.forException<Void>(failure) }
        val householdCaptor = org.mockito.ArgumentCaptor.forClass(Household::class.java)
        assertSame(
            failure,
            assertThrows(Exception::class.java) {
              runBlocking { repository.createHousehold("Home", "creator", type, "retry") }
            })
        verify(batch).set(eq(householdReference), householdCaptor.capture())
        val committed = householdCaptor.value
        `when`(stored.toObject(Household::class.java)).thenReturn(committed)
        `when`(householdReference.get(Source.SERVER)).thenReturn(Tasks.forResult(stored))

        repeat(3) {
          assertEquals(
              committed,
              runBlocking { repository.createHousehold("Home", "creator", type, "retry") })
        }
        verify(batch).commit()
        verify(generator, times(if (type == FridgeType.SHARED) 1 else 0)).generate()
        verify(households, times(4)).document("creator:retry")
      }
    }
  }

  @Test
  fun definitiveFailureCanRetrySameIdentityAndMismatchedRequestCannotOverwrite() =
      runBlocking<Unit> {
        `when`(codeReference.get(Source.SERVER)).thenReturn(Tasks.forResult(missing))
        val failure =
            FirebaseFirestoreException("invalid", FirebaseFirestoreException.Code.INVALID_ARGUMENT)
        `when`(batch.commit()).thenReturn(Tasks.forException(failure), Tasks.forResult(null))
        assertThrows(FirebaseFirestoreException::class.java) {
          runBlocking { repository.createHousehold("Home", "creator", operationId = "retry") }
        }
        val created = repository.createHousehold("Home", "creator", operationId = "retry")
        val stored = mock(DocumentSnapshot::class.java)
        `when`(stored.metadata).thenReturn(mock(SnapshotMetadata::class.java))
        `when`(stored.exists()).thenReturn(true)
        `when`(stored.toObject(Household::class.java)).thenReturn(created)
        `when`(householdReference.get(Source.SERVER)).thenReturn(Tasks.forResult(stored))

        assertThrows(IllegalArgumentException::class.java) {
          runBlocking { repository.createHousehold("Changed", "creator", operationId = "retry") }
        }
        assertThrows(IllegalArgumentException::class.java) {
          runBlocking { repository.createHousehold("Home", "creator", FridgeType.PRIVATE, "retry") }
        }
        verify(batch, times(2)).commit()
      }

  private fun denied() =
      FirebaseFirestoreException("hidden code", FirebaseFirestoreException.Code.PERMISSION_DENIED)
}
