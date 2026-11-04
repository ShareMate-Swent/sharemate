// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.user

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.utils.FirebaseEmulator
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemberNameRepositoryEmulatorTest {
  private val firestore = FirebaseEmulator.firestore
  private val households = HouseholdRepositoryFirestore(firestore)
  private val repository = FirebaseMemberNameRepository(firestore)

  @Before
  fun setup() {
    FirebaseEmulator.clear()
  }

  @After
  fun cleanup() {
    FirebaseEmulator.clear()
  }

  private fun entry(home: String, uid: String) =
      firestore.collection("households").document(home).collection("memberNames").document(uid)

  private fun profile(uid: String) = firestore.collection("users").document(uid)

  private suspend fun resolved(
      home: String,
      uid: String,
      expected: Map<String, String>
  ): Map<String, String> =
      withTimeout(15_000) { repository.observeNames(home, uid).first { it == expected } }

  private fun denied(task: Task<*>) {
    val failure = assertThrows(ExecutionException::class.java) { Tasks.await(task) }
    assertEquals(
        FirebaseFirestoreException.Code.PERMISSION_DENIED,
        (failure.cause as FirebaseFirestoreException).code)
  }

  @Test
  fun membersResolveNamesWithoutReadingEachOthersPrivateProfiles() = runBlocking {
    val alice = FirebaseEmulator.signInAs("name-alice")
    val home = households.createHousehold("Home", alice)
    profile(alice)
        .set(mapOf("displayName" to "Alice", "email" to "private@example.com"), SetOptions.merge())
        .await()
    repository.publishOwnName(home.id, alice, null)
    val bob = FirebaseEmulator.signInAs("name-bob")
    households.joinHousehold(home.inviteCode, bob)
    repository.publishOwnName(home.id, bob, "Bob")
    assertEquals(
        mapOf(alice to "Alice", bob to "Bob"),
        resolved(home.id, bob, mapOf(alice to "Alice", bob to "Bob")))
    assertEquals(
        mapOf("displayName" to "Alice"), entry(home.id, alice).get(Source.SERVER).await().data)
    denied(profile(alice).get(Source.SERVER))
    denied(profile(alice).set(mapOf("displayName" to "Stolen")))
    // Unconstrained enumeration could include departed members and is deliberately denied.
    denied(entry(home.id, alice).parent.get(Source.SERVER))
    FirebaseEmulator.signInAs("name-alice")
    assertEquals(
        "private@example.com", profile(alice).get(Source.SERVER).await().getString("email"))
  }

  @Test
  fun outsidersAndSignedOutUsersCannotReadOrPublishHouseholdNames() = runBlocking {
    val alice = FirebaseEmulator.signInAs("name-alice")
    val home = households.createHousehold("Home", alice)
    repository.publishOwnName(home.id, alice, "Alice")
    val outsider = FirebaseEmulator.signInAs("name-outsider")
    denied(entry(home.id, alice).get(Source.SERVER))
    denied(entry(home.id, outsider).set(mapOf("displayName" to "Outsider")))
    denied(entry(home.id, alice).set(mapOf("displayName" to "Spoofed")))
    denied(profile(alice).get(Source.SERVER))
    FirebaseEmulator.signOut()
    denied(entry(home.id, alice).get(Source.SERVER))
  }

  @Test
  fun directoryRejectsForgedNamesEmailsAndExtraPrivateFields() = runBlocking {
    val alice = FirebaseEmulator.signInAs("name-alice")
    val home = households.createHousehold("Home", alice)
    repository.publishOwnName(home.id, alice, "Alice")
    val bob = FirebaseEmulator.signInAs("name-bob")
    households.joinHousehold(home.inviteCode, bob)
    denied(entry(home.id, alice).set(mapOf("displayName" to "Forged")))
    denied(entry(home.id, alice).delete())
    for (invalid in
        listOf(
            mapOf("displayName" to "Bob", "email" to "private@example.com"),
            mapOf("displayName" to "email@example.com"),
            mapOf("displayName" to "Bob\n<email@example.com>"),
            mapOf("displayName" to ""),
            mapOf("displayName" to "x".repeat(81)))) {
      denied(entry(home.id, bob).set(invalid))
    }
    assertEquals("Alice", entry(home.id, alice).get(Source.SERVER).await().getString("displayName"))
  }

  @Test
  fun departedMembersNamesCannotBeReadEvenIfTheirEntryRemains() = runBlocking {
    val alice = FirebaseEmulator.signInAs("name-alice")
    val home = households.createHousehold("Home", alice)
    repository.publishOwnName(home.id, alice, "Alice")
    val bob = FirebaseEmulator.signInAs("name-bob")
    households.joinHousehold(home.inviteCode, bob)
    repository.publishOwnName(home.id, bob, "Bob")
    households.leaveHousehold(bob)
    denied(entry(home.id, alice).get(Source.SERVER))
    denied(entry(home.id, bob).get(Source.SERVER))
    denied(entry(home.id, bob).set(mapOf("displayName" to "Still here")))
    FirebaseEmulator.signInAs("name-alice")
    denied(entry(home.id, bob).get(Source.SERVER))
    denied(
        entry(home.id, alice)
            .parent
            .whereIn(FieldPath.documentId(), listOf(alice, bob))
            .get(Source.SERVER))
    assertEquals(mapOf(alice to "Alice"), resolved(home.id, alice, mapOf(alice to "Alice")))
  }

  @Test
  fun privateFridgeNamesAreVisibleOnlyToItsCreator() = runBlocking {
    val alice = FirebaseEmulator.signInAs("name-alice")
    val home = households.createHousehold("Private", alice, FridgeType.PRIVATE)
    repository.publishOwnName(home.id, alice, "Alice")
    assertEquals(mapOf(alice to "Alice"), resolved(home.id, alice, mapOf(alice to "Alice")))
    val bob = FirebaseEmulator.signInAs("name-bob")
    denied(entry(home.id, alice).get(Source.SERVER))
    denied(entry(home.id, bob).set(mapOf("displayName" to "Bob")))
  }

  @Test
  fun missingLegacyProfileNameNeverPublishesEmailAndNamesCanRefresh() = runBlocking {
    val alice = FirebaseEmulator.signInAs("name-alice")
    val home = households.createHousehold("Home", alice)
    profile(alice).set(mapOf("email" to "private@example.com"), SetOptions.merge()).await()
    repository.publishOwnName(home.id, alice, null)
    assertFalse(entry(home.id, alice).get(Source.SERVER).await().exists())
    repository.publishOwnName(home.id, alice, "Alice")
    assertEquals(mapOf(alice to "Alice"), resolved(home.id, alice, mapOf(alice to "Alice")))
    repository.publishOwnName(home.id, alice, "Alice Updated")
    assertEquals(
        mapOf(alice to "Alice Updated"), resolved(home.id, alice, mapOf(alice to "Alice Updated")))
  }

  @Test
  fun moreThanTenMembersResolveInAuthorizedBatches() = runBlocking {
    val alice = FirebaseEmulator.signInAs("name-alice")
    val home = households.createHousehold("Home", alice)
    repository.publishOwnName(home.id, alice, "Alice")
    val expected = mutableMapOf(alice to "Alice")
    repeat(11) { i ->
      val uid = FirebaseEmulator.signInAs("name-member-$i")
      households.joinHousehold(home.inviteCode, uid)
      repository.publishOwnName(home.id, uid, "Member $i")
      expected[uid] = "Member $i"
    }
    FirebaseEmulator.signInAs("name-alice")
    assertEquals(expected, resolved(home.id, alice, expected))
  }
}
