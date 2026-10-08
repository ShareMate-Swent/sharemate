// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.household

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.sharemate.model.FirestoreCollections
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HouseholdRepositoryFirestoreTest {
  private lateinit var repository: HouseholdRepositoryFirestore

  @Before
  fun setUp() {
    clearEmulatorData()
    repository = HouseholdRepositoryFirestore(firestore)
  }

  @After
  fun tearDown() {
    clearEmulatorData()
  }

  @Test
  fun createHouseholdStoresHouseholdAndUpdatesExistingUser() = runBlocking {
    val creatorId = "existing-creator"
    val userReference = firestore.collection(FirestoreCollections.USERS).document(creatorId)
    Tasks.await(userReference.set(mapOf("uid" to creatorId, "displayName" to "Creator")))

    val household = repository.createHousehold("Home", creatorId)
    val storedHousehold =
        Tasks.await(
                firestore.collection(FirestoreCollections.HOUSEHOLDS).document(household.id).get())
            .toObject(Household::class.java)
    val storedUser = Tasks.await(userReference.get())

    assertTrue(household.id.isNotBlank())
    assertEquals("Home", household.name)
    assertValidInviteCode(household.inviteCode)
    assertEquals(listOf(creatorId), household.memberIds)
    assertEquals(creatorId, household.createdBy)
    assertNotNull(household.createdAt)
    assertEquals(household, storedHousehold)
    assertEquals(household.id, storedUser.getString("householdId"))
    assertEquals("Creator", storedUser.getString("displayName"))
    val storedInviteCode =
        Tasks.await(
            firestore
                .collection(FirestoreCollections.INVITE_CODES)
                .document(household.inviteCode)
                .get())
    assertEquals(household.id, storedInviteCode.getString("householdId"))
  }

  @Test
  fun createHouseholdCreatesMissingUserDocumentWithHouseholdId() = runBlocking {
    val creatorId = "missing-creator"

    val household = repository.createHousehold("New Home", creatorId)
    val userSnapshot =
        Tasks.await(firestore.collection(FirestoreCollections.USERS).document(creatorId).get())

    assertTrue(userSnapshot.exists())
    assertEquals(household.id, userSnapshot.getString("householdId"))
  }

  @Test
  fun blankNameIsRejectedWithoutWritingAnything() = runBlocking {
    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { repository.createHousehold(" ", "creator") }
    }

    assertTrue(Tasks.await(firestore.collection(FirestoreCollections.HOUSEHOLDS).get()).isEmpty)
    assertFalseDocumentExists(FirestoreCollections.USERS, "creator")
  }

  @Test
  fun inviteCodeCollisionRetriesWithAnotherCode() = runBlocking {
    val firstCode = InviteCodeGenerator(Random(1234)).generate()
    val repositoryWithSeed =
        HouseholdRepositoryFirestore(firestore, InviteCodeGenerator(Random(1234)))
    Tasks.await(
        firestore
            .collection(FirestoreCollections.INVITE_CODES)
            .document(firstCode)
            .set(mapOf("householdId" to "existing-household")))

    val created = repositoryWithSeed.createHousehold("Home", "creator")

    assertNotEquals(firstCode, created.inviteCode)
    assertEquals(1, Tasks.await(firestore.collection(FirestoreCollections.HOUSEHOLDS).get()).size())
  }

  @Test
  fun maximumInviteCodeAttemptsThrows() = runBlocking {
    val repositoryWithRepeatedCode =
        HouseholdRepositoryFirestore(firestore, InviteCodeGenerator(ConstantRandom()))
    Tasks.await(
        firestore
            .collection(FirestoreCollections.INVITE_CODES)
            .document("AAAAAA")
            .set(mapOf("householdId" to "existing-household")))

    assertThrows(IllegalStateException::class.java) {
      runBlocking { repositoryWithRepeatedCode.createHousehold("Home", "creator") }
    }

    assertEquals(0, Tasks.await(firestore.collection(FirestoreCollections.HOUSEHOLDS).get()).size())
    assertFalseDocumentExists(FirestoreCollections.USERS, "creator")
  }

  @Test
  fun joinHouseholdWithValidCodeAddsMemberAndUpdatesUser() = runBlocking {
    val created = repository.createHousehold("Home", "creator")

    val joined = repository.joinHousehold(created.inviteCode, "member")
    val storedUser =
        Tasks.await(firestore.collection(FirestoreCollections.USERS).document("member").get())

    assertEquals(listOf("creator", "member"), joined.memberIds)
    assertEquals(created.id, storedUser.getString("householdId"))
    assertEquals(joined, repository.getHousehold(created.id))
  }

  @Test
  fun joinHouseholdAcceptsLowercaseCodeAndSurroundingSpaces() = runBlocking {
    val created = repository.createHousehold("Home", "creator")

    val joined = repository.joinHousehold("  ${created.inviteCode.lowercase()}  ", "member")
    val storedHousehold =
        Tasks.await(
                firestore.collection(FirestoreCollections.HOUSEHOLDS).document(created.id).get())
            .toObject(Household::class.java)
    val storedUser =
        Tasks.await(firestore.collection(FirestoreCollections.USERS).document("member").get())

    assertEquals(listOf("creator", "member"), joined.memberIds)
    assertEquals(listOf("creator", "member"), storedHousehold?.memberIds)
    assertEquals(created.id, storedUser.getString("householdId"))
  }

  @Test
  fun joinHouseholdWithUnknownCodeThrowsWithoutWriting() = runBlocking {
    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { repository.joinHousehold("UNKNOWN", "member") }
    }

    assertTrue(Tasks.await(firestore.collection(FirestoreCollections.HOUSEHOLDS).get()).isEmpty)
    assertFalseDocumentExists(FirestoreCollections.USERS, "member")
  }

  @Test
  fun joiningHouseholdAlreadyContainingUserDoesNotDuplicateMember() = runBlocking {
    val created = repository.createHousehold("Home", "creator")

    val joined = repository.joinHousehold(created.inviteCode, "creator")
    val storedHousehold =
        Tasks.await(
                firestore.collection(FirestoreCollections.HOUSEHOLDS).document(created.id).get())
            .toObject(Household::class.java)
    val storedUser =
        Tasks.await(firestore.collection(FirestoreCollections.USERS).document("creator").get())

    assertEquals(created, joined)
    assertEquals(listOf("creator"), joined.memberIds)
    assertEquals(listOf("creator"), storedHousehold?.memberIds)
    assertEquals(created.id, storedUser.getString("householdId"))
  }

  @Test
  fun householdLookupsReturnHouseholdOrNull() = runBlocking {
    val household = repository.createHousehold("Home", "creator")

    assertEquals(household, repository.getHousehold(household.id))
    assertEquals(household, repository.getHouseholdForUser("creator"))
    assertNull(repository.getHousehold("unknown"))
    assertNull(repository.getHouseholdForUser("unknown"))
  }

  @Test
  fun firestoreTaskAdapterPropagatesFailure() {
    val unavailableApp =
        FirebaseApp.initializeApp(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FirebaseOptions.Builder()
                .setApplicationId("1:694271493315:android:unavailable")
                .setProjectId("sharemate-swent")
                .build(),
            UNAVAILABLE_APP_NAME)
    val unavailableFirestore = FirebaseFirestore.getInstance(unavailableApp)
    unavailableFirestore.useEmulator(EMULATOR_HOST, UNAVAILABLE_FIRESTORE_PORT)
    val unavailableRepository = HouseholdRepositoryFirestore(unavailableFirestore)

    assertThrows(Exception::class.java) {
      runBlocking { unavailableRepository.getHousehold("unknown") }
    }
  }

  private fun assertValidInviteCode(code: String) {
    assertEquals(6, code.length)
    assertTrue(code.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" })
  }

  private fun assertFalseDocumentExists(collection: String, document: String) {
    assertTrue(!Tasks.await(firestore.collection(collection).document(document).get()).exists())
  }

  private fun clearEmulatorData() {
    runBlocking {
      for (collection in
          listOf(
              FirestoreCollections.USERS,
              FirestoreCollections.HOUSEHOLDS,
              FirestoreCollections.INVITE_CODES)) {
        val snapshot = Tasks.await(firestore.collection(collection).get())
        if (!snapshot.isEmpty) {
          val batch = firestore.batch()
          snapshot.documents.forEach { batch.delete(it.reference) }
          Tasks.await(batch.commit())
        }
      }
    }
  }

  private class ConstantRandom : Random() {
    override fun nextBits(bitCount: Int): Int = 0
  }

  private companion object {
    const val EMULATOR_HOST = "10.0.2.2"
    const val FIRESTORE_PORT = 8080
    const val UNAVAILABLE_FIRESTORE_PORT = 18080
    const val UNAVAILABLE_APP_NAME = "unavailable-firestore-test"
    lateinit var firestore: FirebaseFirestore

    @JvmStatic
    @BeforeClass
    fun configureFirestoreEmulator() {
      firestore = FirebaseFirestore.getInstance()
      firestore.useEmulator(EMULATOR_HOST, FIRESTORE_PORT)
    }
  }
}
