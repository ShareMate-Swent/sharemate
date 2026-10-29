// Co-authored-by: OpenAI Codex
package com.android.sharemate.model.household

import com.android.sharemate.model.FirestoreCollections
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HouseholdPrivateLeaveTest {
  private val firestore = mock(FirebaseFirestore::class.java)
  private val households = mock(CollectionReference::class.java)
  private val query = mock(Query::class.java)
  private val snapshot = mock(QuerySnapshot::class.java)
  private val privateFridge = mock(DocumentSnapshot::class.java)
  private val repository = HouseholdRepositoryFirestore(firestore)

  @Test
  fun privateMembershipIsRejectedBeforeReadingProfileOrWriting() {
    assertPrivateLeaveRejected(listOf(privateFridge))
  }

  @Test
  fun mixedMembershipsAreRejectedBeforeAnySharedMembershipIsRemoved() {
    val sharedFridge = mock(DocumentSnapshot::class.java)
    `when`(sharedFridge.getString("type")).thenReturn("SHARED")
    assertPrivateLeaveRejected(listOf(sharedFridge, privateFridge))
  }

  private fun assertPrivateLeaveRejected(documents: List<DocumentSnapshot>) {
    `when`(firestore.collection(FirestoreCollections.HOUSEHOLDS)).thenReturn(households)
    `when`(households.whereArrayContains("memberIds", "owner")).thenReturn(query)
    `when`(query.get()).thenReturn(Tasks.forResult(snapshot))
    `when`(snapshot.documents).thenReturn(documents)
    `when`(privateFridge.getString("type")).thenReturn("PRIVATE")

    assertThrows(PrivateHouseholdLeaveException::class.java) {
      runBlocking { repository.leaveHousehold("owner") }
    }
    verify(firestore, never()).collection(FirestoreCollections.USERS)
    verify(firestore, never()).batch()
  }
}
