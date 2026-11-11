// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.user

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.utils.FirebaseEmulator
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.*
import com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
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
  private lateinit var alice: String
  private lateinit var home: Household

  @Before
  fun setup() = runBlocking {
    check(firestore.app.options.projectId?.startsWith("demo-") == true)
    FirebaseEmulator.clear()
    alice = FirebaseEmulator.signInAs("name-alice")
    home = households.createHousehold("Home", alice)
  }

  @After
  fun cleanup() {
    FirebaseEmulator.clear()
  }

  private fun entry(home: String, uid: String) =
      firestore.collection("households").document(home).collection("memberNames").document(uid)

  private suspend fun cacheNames(home: String, ids: List<String>) =
      entry(home, ids.first())
          .parent
          .whereIn(FieldPath.documentId(), ids.sorted())
          .get(Source.SERVER)
          .await()

  private fun profile(uid: String) = firestore.collection("users").document(uid)

  private suspend fun publish(home: String, uid: String, name: String?) =
      repository.queueOwnName(home, uid, name).await().getOrThrow()

  private suspend fun cachedName(home: String, uid: String, source: Source = Source.CACHE) =
      entry(home, uid).get(source).await().getString("displayName")

  private suspend fun resolved(
      home: String,
      uid: String,
      expected: Map<String, String>
  ): Map<String, String> =
      withTimeout(15_000) { repository.observeNames(home, uid).first { it == expected } }

  private fun denied(task: Task<*>) {
    val failure = assertThrows(ExecutionException::class.java) { Tasks.await(task) }
    assertEquals(PERMISSION_DENIED, (failure.cause as FirebaseFirestoreException).code)
  }

  @Test
  fun membersResolveNamesWithoutReadingEachOthersPrivateProfiles() = runBlocking {
    profile(alice)
        .set(mapOf("displayName" to "Alice", "email" to "private@example.com"), SetOptions.merge())
        .await()
    publish(home.id, alice, null)
    val bob = FirebaseEmulator.signInAs("name-bob")
    households.joinHousehold(home.inviteCode, bob)
    publish(home.id, bob, "Bob")
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
    publish(home.id, alice, "Alice")
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
    publish(home.id, alice, "Alice")
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
    publish(home.id, alice, "Alice")
    val bob = FirebaseEmulator.signInAs("name-bob")
    households.joinHousehold(home.inviteCode, bob)
    publish(home.id, bob, "Bob")
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
    home = households.createHousehold("Private", alice, FridgeType.PRIVATE)
    publish(home.id, alice, "Alice")
    assertEquals(mapOf(alice to "Alice"), resolved(home.id, alice, mapOf(alice to "Alice")))
    val bob = FirebaseEmulator.signInAs("name-bob")
    denied(entry(home.id, alice).get(Source.SERVER))
    denied(entry(home.id, bob).set(mapOf("displayName" to "Bob")))
  }

  @Test
  fun missingLegacyProfileNameNeverPublishesEmailAndNamesCanRefresh() = runBlocking {
    profile(alice).set(mapOf("email" to "private@example.com"), SetOptions.merge()).await()
    publish(home.id, alice, null)
    assertFalse(entry(home.id, alice).get(Source.SERVER).await().exists())
    publish(home.id, alice, "Alice")
    assertEquals(mapOf(alice to "Alice"), resolved(home.id, alice, mapOf(alice to "Alice")))
    publish(home.id, alice, "Alice Updated")
    assertEquals(
        mapOf(alice to "Alice Updated"), resolved(home.id, alice, mapOf(alice to "Alice Updated")))
  }

  @Test
  fun moreThanTenMembersResolveInAuthorizedBatches() = runBlocking {
    publish(home.id, alice, "Alice")
    val expected = mutableMapOf(alice to "Alice")
    repeat(11) { i ->
      val uid = FirebaseEmulator.signInAs("name-member-$i")
      households.joinHousehold(home.inviteCode, uid)
      publish(home.id, uid, "Member $i")
      expected[uid] = "Member $i"
    }
    FirebaseEmulator.signInAs("name-alice")
    assertEquals(expected, resolved(home.id, alice, expected))
  }

  @Test
  fun offlineNewObservationHidesCachedNamesUntilServerConfirmation() = runBlocking {
    publish(home.id, alice, "Alice")
    resolved(home.id, alice, mapOf(alice to "Alice"))
    cacheNames(home.id, listOf(alice))
    firestore.disableNetwork().await()
    val names = CompletableDeferred<Map<String, String>>()
    val job = launch {
      repository.observeNames(home.id, alice).collect { if (it.isNotEmpty()) names.complete(it) }
    }
    try {
      assertEquals("Alice", cachedName(home.id, alice))
      // This is an assertion of non-delivery while offline, not a startup delay.
      assertNull(withTimeoutOrNull(1_500) { names.await() })
      firestore.enableNetwork().await()
      assertEquals(mapOf(alice to "Alice"), withTimeout(15_000) { names.await() })
    } finally {
      firestore.enableNetwork().await()
      job.cancelAndJoin()
    }
  }

  @Test
  fun revokedMemberOpeningOfflineNeverReceivesCachedNames() = runBlocking {
    publish(home.id, alice, "Alice")
    val bob = FirebaseEmulator.signInAs("revocation-name-bob")
    households.joinHousehold(home.inviteCode, bob)
    publish(home.id, bob, "Bob")
    resolved(home.id, bob, mapOf(alice to "Alice", bob to "Bob"))
    cacheNames(home.id, listOf(alice, bob))
    firestore.collection("households").document(home.id).get(Source.SERVER).await()
    firestore.disableNetwork().await()
    val leaked = CompletableDeferred<Map<String, String>>()
    val denied = CompletableDeferred<Throwable>()
    var observation: kotlinx.coroutines.Job? = null
    try {
      // Another authenticated client leaves while this client remains offline with stale data.
      FirebaseEmulator.secondClientAs("revocation-name-bob").use { other ->
        HouseholdRepositoryFirestore(other.firestore).leaveHousehold(bob)
      }
      assertTrue(
          firestore
              .collection("households")
              .document(home.id)
              .get(Source.CACHE)
              .await()
              .get("memberIds")
              .let { it as List<*> }
              .contains(bob))
      assertEquals("Alice", cachedName(home.id, alice))
      observation = launch {
        try {
          repository.observeNames(home.id, bob).collect { if (it.isNotEmpty()) leaked.complete(it) }
        } catch (failure: FirebaseFirestoreException) {
          denied.complete(failure)
        }
      }
      assertNull(withTimeoutOrNull(1_500) { leaked.await() })
      firestore.enableNetwork().await()
      assertEquals(
          PERMISSION_DENIED,
          (withTimeout(15_000) { denied.await() } as FirebaseFirestoreException).code)
      assertFalse(leaked.isCompleted)
    } finally {
      firestore.enableNetwork().await()
      observation?.cancelAndJoin()
    }
  }

  @Test
  fun offlineSetAndDeleteQueueThenConfirmOnReconnection() = runBlocking {
    publish(home.id, alice, "Alice")
    for (name in listOf("Alice Updated", null)) {
      firestore.disableNetwork().await()
      try {
        val confirmation = withTimeout(2_000) { repository.queueOwnName(home.id, alice, name) }
        assertFalse(confirmation.isCompleted)
        val local = entry(home.id, alice).get(Source.CACHE).await()
        assertEquals(name, local.getString("displayName"))
        assertEquals(name != null, local.exists())
        // Missing-document snapshots omit pending-delete metadata; the handle tracks it.
        if (local.exists()) assertTrue(local.metadata.hasPendingWrites())
        firestore.enableNetwork().await()
        withTimeout(15_000) { confirmation.await() }.getOrThrow()
        assertEquals(name, cachedName(home.id, alice, Source.SERVER))
      } finally {
        firestore.enableNetwork().await()
      }
    }
  }

  @Test
  fun offlinePublicationReportsPermissionFailureAfterReconnection() = runBlocking {
    val outsider = FirebaseEmulator.signInAs("publish-outsider")
    firestore.disableNetwork().await()
    try {
      val confirmation =
          withTimeout(2_000) { repository.queueOwnName(home.id, outsider, "Outsider") }
      assertFalse(confirmation.isCompleted)
      firestore.enableNetwork().await()
      val failure = withTimeout(15_000) { confirmation.await() }.exceptionOrNull()
      assertEquals(PERMISSION_DENIED, (failure as FirebaseFirestoreException).code)
    } finally {
      firestore.enableNetwork().await()
    }
  }
}
