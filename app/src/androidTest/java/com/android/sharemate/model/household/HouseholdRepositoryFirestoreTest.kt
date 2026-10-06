// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.household

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.sharemate.model.FirestoreCollections
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Date
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
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HouseholdRepositoryFirestoreTest {
  private lateinit var firestore: FirebaseFirestore
  private lateinit var repository: HouseholdRepositoryFirestore

  @Before
  fun setUp() {
    firestore = FirebaseFirestore.getInstance()
    firestore.useEmulator(EMULATOR_HOST, FIRESTORE_PORT)
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
            .collection(FirestoreCollections.HOUSEHOLDS)
            .document("existing-household")
            .set(
                Household(
                    id = "existing-household",
                    name = "Existing",
                    inviteCode = firstCode,
                    memberIds = listOf("other"),
                    createdBy = "other",
                    createdAt = Date())))

    val created = repositoryWithSeed.createHousehold("Home", "creator")

    assertNotEquals(firstCode, created.inviteCode)
    assertEquals(2, Tasks.await(firestore.collection(FirestoreCollections.HOUSEHOLDS).get()).size())
  }

  @Test
  fun maximumInviteCodeAttemptsThrows() = runBlocking {
    val repositoryWithRepeatedCode =
        HouseholdRepositoryFirestore(firestore, InviteCodeGenerator(ConstantRandom()))
    Tasks.await(
        firestore
            .collection(FirestoreCollections.HOUSEHOLDS)
            .document("existing-household")
            .set(mapOf("inviteCode" to "AAAAAA")))

    assertThrows(IllegalStateException::class.java) {
      runBlocking { repositoryWithRepeatedCode.createHousehold("Home", "creator") }
    }

    assertEquals(1, Tasks.await(firestore.collection(FirestoreCollections.HOUSEHOLDS).get()).size())
    assertFalseDocumentExists(FirestoreCollections.USERS, "creator")
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
      for (collection in listOf(FirestoreCollections.USERS, FirestoreCollections.HOUSEHOLDS)) {
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
  }
}
