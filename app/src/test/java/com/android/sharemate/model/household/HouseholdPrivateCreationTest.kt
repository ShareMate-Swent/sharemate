// Co-authored-by: OpenAI Codex
package com.android.sharemate.model.household

import com.android.sharemate.model.FirestoreCollections
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.SnapshotMetadata
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.WriteBatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HouseholdPrivateCreationTest {
  private val firestore = mock(FirebaseFirestore::class.java)
  private val households = mock(CollectionReference::class.java)
  private val users = mock(CollectionReference::class.java)
  private val householdReference = mock(DocumentReference::class.java)
  private val userReference = mock(DocumentReference::class.java)
  private val batch = mock(WriteBatch::class.java)
  private val generator = mock(InviteCodeGenerator::class.java)
  private val repository = HouseholdRepositoryFirestore(firestore, generator)

  @Before
  fun setUp() {
    `when`(firestore.collection(FirestoreCollections.HOUSEHOLDS)).thenReturn(households)
    `when`(firestore.collection(FirestoreCollections.USERS)).thenReturn(users)
    `when`(households.document(anyString())).thenReturn(householdReference)
    val missing = mock(DocumentSnapshot::class.java)
    `when`(missing.metadata).thenReturn(mock(SnapshotMetadata::class.java))
    `when`(householdReference.get(Source.SERVER)).thenReturn(Tasks.forResult(missing))
    `when`(householdReference.id).thenReturn("new-household")
    `when`(users.document("creator")).thenReturn(userReference)
    `when`(firestore.batch()).thenReturn(batch)
    `when`(batch.set(eq(householdReference), any(Household::class.java))).thenReturn(batch)
    `when`(batch.set(userReference, mapOf("householdId" to "new-household"), SetOptions.merge()))
        .thenReturn(batch)
    `when`(batch.commit()).thenReturn(Tasks.forResult(null))
  }

  @Test
  fun privateCreationWritesHouseholdAndProfileWithoutGeneratingOrAccessingInvites() =
      runBlocking<Unit> {
        val created = repository.createHousehold("Mine", "creator", FridgeType.PRIVATE)

        assertEquals("", created.inviteCode)
        assertEquals(FridgeType.PRIVATE, created.type)
        assertEquals(listOf("creator"), created.memberIds)
        assertEquals("creator", created.createdBy)
        verify(batch).set(householdReference, created)
        verify(batch).set(userReference, mapOf("householdId" to created.id), SetOptions.merge())
        verify(batch).commit()
        verifyNoMoreInteractions(batch)
        verifyNoInteractions(generator)
        verify(firestore, never()).collection(FirestoreCollections.INVITE_CODES)
      }

  @Test
  fun privateCommitFailurePropagatesWithoutGeneratingAnInviteOrRetrying() {
    val failure = IllegalStateException("unavailable")
    `when`(batch.commit()).thenReturn(Tasks.forException(failure))

    val thrown =
        assertThrows(IllegalStateException::class.java) {
          runBlocking { repository.createHousehold("Mine", "creator", FridgeType.PRIVATE) }
        }

    assertSame(failure, thrown)
    verify(batch).commit()
    verifyNoInteractions(generator)
    verify(firestore, never()).collection(FirestoreCollections.INVITE_CODES)
  }

  @Test
  fun sharedCreationStillGeneratesChecksAndWritesAnInvite() =
      runBlocking<Unit> {
        val codes = mock(CollectionReference::class.java)
        val codeReference = mock(DocumentReference::class.java)
        val missing = mock(DocumentSnapshot::class.java)
        `when`(firestore.collection(FirestoreCollections.INVITE_CODES)).thenReturn(codes)
        `when`(generator.generate()).thenReturn("ABC123")
        `when`(codes.document("ABC123")).thenReturn(codeReference)
        `when`(codeReference.get(Source.SERVER)).thenReturn(Tasks.forResult(missing))
        `when`(batch.set(codeReference, mapOf("householdId" to "new-household"))).thenReturn(batch)

        val created = repository.createHousehold("Home", "creator", FridgeType.SHARED)

        assertEquals("ABC123", created.inviteCode)
        verify(generator).generate()
        verify(codeReference).get(Source.SERVER)
        verify(batch).set(codeReference, mapOf("householdId" to created.id))
        verify(batch).set(householdReference, created)
        verify(batch).commit()
      }

  @Test
  fun invalidPrivateInputIsRejectedBeforeAccessingFirestore() {
    for ((name, creator) in listOf(" " to "creator", "Mine" to " ")) {
      assertThrows(IllegalArgumentException::class.java) {
        runBlocking { repository.createHousehold(name, creator, FridgeType.PRIVATE) }
      }
    }
    verifyNoInteractions(firestore, generator)
  }
}
