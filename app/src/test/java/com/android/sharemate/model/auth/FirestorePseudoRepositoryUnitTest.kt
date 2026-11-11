// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.auth

import com.android.sharemate.model.FirestoreCollections
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Transaction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*

class FirestorePseudoRepositoryUnitTest {
  private val firestore = mock(FirebaseFirestore::class.java)
  private val claims = mock(CollectionReference::class.java)
  private val profiles = mock(CollectionReference::class.java)
  private val claim = mock(DocumentReference::class.java)
  private val profile = mock(DocumentReference::class.java)
  private val transaction = mock(Transaction::class.java)
  private val claimSnapshot = mock(DocumentSnapshot::class.java)
  private val profileSnapshot = mock(DocumentSnapshot::class.java)
  private val repository = FirestorePseudoRepository(firestore)

  init {
    `when`(firestore.collection(FirestoreCollections.PSEUDO_CLAIMS)).thenReturn(claims)
    `when`(firestore.collection(FirestoreCollections.PROFILES)).thenReturn(profiles)
    `when`(claims.document(pseudoClaimId(PSEUDO))).thenReturn(claim)
    `when`(profiles.document(UID)).thenReturn(profile)
    `when`(transaction.get(claim)).thenReturn(claimSnapshot)
    `when`(transaction.get(profile)).thenReturn(profileSnapshot)
    `when`(firestore.runTransaction<Unit>(any())).thenAnswer {
      val callback = it.getArgument<Transaction.Function<Unit>>(0)
      Tasks.forResult(callback.apply(transaction))
    }
  }

  @Test
  fun rejectsBlankInputsBeforeAccessingFirestore() =
      runBlocking<Unit> {
        for ((uid, pseudo) in listOf("" to PSEUDO, UID to " \u200B ")) {
          assertTrue(
              runCatching { repository.claim(uid, pseudo) }.exceptionOrNull()
                  is IllegalArgumentException)
        }
        verify(firestore, never()).runTransaction<Unit>(any())
      }

  @Test
  fun claimsNewNameAndAcceptsCurrentAndLegacyOwners() =
      runBlocking<Unit> {
        `when`(claimSnapshot.exists()).thenReturn(false, true, true)
        `when`(claimSnapshot.getString("uid")).thenReturn(UID, null)
        `when`(claimSnapshot.get("legacyUids")).thenReturn(listOf(UID))

        repeat(3) { repository.claim(UID, PSEUDO) }

        verify(transaction).set(claim, mapOf("uid" to UID))
      }

  @Test
  fun rejectsExistingClaimOwnedByAnotherUser() =
      runBlocking<Unit> {
        `when`(claimSnapshot.exists()).thenReturn(true)
        `when`(claimSnapshot.getString("uid")).thenReturn(OTHER_UID)
        `when`(claimSnapshot.get("legacyUids")).thenReturn(listOf(OTHER_UID))

        val failure = runCatching { repository.claim(UID, PSEUDO) }.exceptionOrNull()

        assertTrue(failure is AuthException)
        assertEquals(AuthError.PSEUDO_TAKEN, (failure as AuthException).error)
        verify(transaction, never()).set(any(DocumentReference::class.java), any())
      }

  @Test
  fun mapsPermissionDeniedButPropagatesOtherFirestoreErrors() =
      runBlocking<Unit> {
        val denied =
            FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
        val unavailable =
            FirebaseFirestoreException("unavailable", FirebaseFirestoreException.Code.UNAVAILABLE)
        doReturn(Tasks.forException<Unit>(denied), Tasks.forException<Unit>(unavailable))
            .`when`(firestore)
            .runTransaction<Unit>(any())

        val deniedFailure = runCatching { repository.claim(UID, PSEUDO) }.exceptionOrNull()
        assertTrue(deniedFailure is AuthException)
        assertEquals(AuthError.PSEUDO_TAKEN, (deniedFailure as AuthException).error)
        assertSame(unavailable, runCatching { repository.claim(UID, PSEUDO) }.exceptionOrNull())
      }

  @Test
  fun releaseDeletesOnlyAnOwnedClaimNotUsedByProfile() =
      runBlocking<Unit> {
        `when`(claimSnapshot.getString("uid")).thenReturn(UID, UID, OTHER_UID)
        `when`(profileSnapshot.getString("displayName")).thenReturn("Current", PSEUDO, "Current")

        repeat(3) { repository.release(UID, PSEUDO) }

        verify(transaction, times(3)).get(claim)
        verify(transaction, times(3)).get(profile)
        verify(transaction).delete(claim)
      }

  private companion object {
    const val UID = "user"
    const val OTHER_UID = "other"
    const val PSEUDO = "Alex"
  }
}
