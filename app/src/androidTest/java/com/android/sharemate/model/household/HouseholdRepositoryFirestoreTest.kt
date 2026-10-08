// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.household

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.utils.FirebaseEmulator
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs against the Firestore emulator with `firestore.rules` enforced, so every operation is
 * performed as the signed-in user it concerns.
 */
@RunWith(AndroidJUnit4::class)
class HouseholdRepositoryFirestoreTest {
  private val firestore = FirebaseEmulator.firestore
  private lateinit var repository: HouseholdRepositoryFirestore

  @Before
  fun setUp() {
    FirebaseEmulator.clear()
    repository = HouseholdRepositoryFirestore(firestore)
  }

  @After
  fun tearDown() {
    FirebaseEmulator.clear()
  }

  @Test
  fun createHouseholdStoresHouseholdAndUpdatesExistingUser() = runBlocking {
    val creatorId = FirebaseEmulator.signInAs("creator")
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
    assertEquals(household.id, storedHouseholdIdForInviteCode(household.inviteCode))
  }

  @Test
  fun createHouseholdCreatesMissingUserDocumentWithHouseholdId() = runBlocking {
    val creatorId = FirebaseEmulator.signInAs("missing-creator")

    val household = repository.createHousehold("New Home", creatorId)
    val userSnapshot =
        Tasks.await(firestore.collection(FirestoreCollections.USERS).document(creatorId).get())

    assertTrue(userSnapshot.exists())
    assertEquals(household.id, userSnapshot.getString("householdId"))
  }

  @Test
  fun blankNameIsRejectedWithoutWritingAnything() = runBlocking {
    val creatorId = FirebaseEmulator.signInAs("creator")

    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { repository.createHousehold(" ", creatorId) }
    }

    assertNull(repository.getHouseholdForUser(creatorId))
    assertUserDocumentDoesNotExist(creatorId)
  }

  @Test
  fun inviteCodeCollisionRetriesWithAnotherCode() = runBlocking {
    val firstCode = InviteCodeGenerator(Random(1234)).generate()
    val otherId = FirebaseEmulator.signInAs("other")
    val existing =
        HouseholdRepositoryFirestore(firestore, InviteCodeGenerator(Random(1234)))
            .createHousehold("Existing", otherId)
    assertEquals(firstCode, existing.inviteCode)

    val creatorId = FirebaseEmulator.signInAs("creator")
    val created =
        HouseholdRepositoryFirestore(firestore, InviteCodeGenerator(Random(1234)))
            .createHousehold("Home", creatorId)

    assertNotEquals(firstCode, created.inviteCode)
    assertEquals(existing.id, storedHouseholdIdForInviteCode(firstCode))
    assertEquals(created.id, storedHouseholdIdForInviteCode(created.inviteCode))
  }

  @Test
  fun maximumInviteCodeAttemptsThrows() = runBlocking {
    val otherId = FirebaseEmulator.signInAs("other")
    val existing =
        HouseholdRepositoryFirestore(firestore, InviteCodeGenerator(ConstantRandom()))
            .createHousehold("Existing", otherId)
    val creatorId = FirebaseEmulator.signInAs("creator")
    val repositoryWithRepeatedCode =
        HouseholdRepositoryFirestore(firestore, InviteCodeGenerator(ConstantRandom()))

    assertThrows(IllegalStateException::class.java) {
      runBlocking { repositoryWithRepeatedCode.createHousehold("Home", creatorId) }
    }

    assertEquals(existing.id, storedHouseholdIdForInviteCode(existing.inviteCode))
    assertNull(repository.getHouseholdForUser(creatorId))
    assertUserDocumentDoesNotExist(creatorId)
  }

  @Test
  fun joinHouseholdWithValidCodeAddsMemberAndUpdatesUser() = runBlocking {
    val creatorId = FirebaseEmulator.signInAs("creator")
    val created = repository.createHousehold("Home", creatorId)
    val memberId = FirebaseEmulator.signInAs("member")

    val joined = repository.joinHousehold(created.inviteCode, memberId)
    val storedUser =
        Tasks.await(firestore.collection(FirestoreCollections.USERS).document(memberId).get())

    assertEquals(listOf(creatorId, memberId), joined.memberIds)
    assertEquals(created.id, storedUser.getString("householdId"))
    assertEquals(joined, repository.getHousehold(created.id))
  }

  @Test
  fun joinHouseholdAcceptsLowercaseCodeAndSurroundingSpaces() = runBlocking {
    val creatorId = FirebaseEmulator.signInAs("creator")
    val created = repository.createHousehold("Home", creatorId)
    val memberId = FirebaseEmulator.signInAs("member")

    val joined = repository.joinHousehold("  ${created.inviteCode.lowercase()}  ", memberId)
    val storedHousehold =
        Tasks.await(
                firestore.collection(FirestoreCollections.HOUSEHOLDS).document(created.id).get())
            .toObject(Household::class.java)
    val storedUser =
        Tasks.await(firestore.collection(FirestoreCollections.USERS).document(memberId).get())

    assertEquals(listOf(creatorId, memberId), joined.memberIds)
    assertEquals(listOf(creatorId, memberId), storedHousehold?.memberIds)
    assertEquals(created.id, storedUser.getString("householdId"))
  }

  @Test
  fun joinHouseholdWithUnknownCodeThrowsWithoutWriting() = runBlocking {
    val memberId = FirebaseEmulator.signInAs("member")

    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { repository.joinHousehold("UNKNOWN", memberId) }
    }

    assertNull(repository.getHouseholdForUser(memberId))
    assertUserDocumentDoesNotExist(memberId)
  }

  @Test
  fun joiningHouseholdAlreadyContainingUserDoesNotDuplicateMember() = runBlocking {
    val creatorId = FirebaseEmulator.signInAs("creator")
    val created = repository.createHousehold("Home", creatorId)

    val joined = repository.joinHousehold(created.inviteCode, creatorId)
    val storedHousehold =
        Tasks.await(
                firestore.collection(FirestoreCollections.HOUSEHOLDS).document(created.id).get())
            .toObject(Household::class.java)
    val storedUser =
        Tasks.await(firestore.collection(FirestoreCollections.USERS).document(creatorId).get())

    assertEquals(created, joined)
    assertEquals(listOf(creatorId), joined.memberIds)
    assertEquals(listOf(creatorId), storedHousehold?.memberIds)
    assertEquals(created.id, storedUser.getString("householdId"))
  }

  @Test
  fun householdLookupsReturnHouseholdOrNull() = runBlocking {
    val creatorId = FirebaseEmulator.signInAs("creator")
    val household = repository.createHousehold("Home", creatorId)

    assertEquals(household, repository.getHousehold(household.id))
    assertEquals(household, repository.getHouseholdForUser(creatorId))
    assertNull(repository.getHousehold("unknown"))

    val outsiderId = FirebaseEmulator.signInAs("outsider")
    assertNull(repository.getHouseholdForUser(outsiderId))
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
    unavailableFirestore.useEmulator(FirebaseEmulator.HOST, UNAVAILABLE_FIRESTORE_PORT)
    val unavailableRepository = HouseholdRepositoryFirestore(unavailableFirestore)

    assertThrows(Exception::class.java) {
      runBlocking { unavailableRepository.getHousehold("unknown") }
    }
  }

  private fun assertValidInviteCode(code: String) {
    assertEquals(6, code.length)
    assertTrue(code.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" })
  }

  /** Must be called while signed in as [userId], the only user allowed to read that document. */
  private fun assertUserDocumentDoesNotExist(userId: String) {
    assertFalse(
        Tasks.await(firestore.collection(FirestoreCollections.USERS).document(userId).get())
            .exists())
  }

  private fun storedHouseholdIdForInviteCode(inviteCode: String): String? =
      Tasks.await(
              firestore.collection(FirestoreCollections.INVITE_CODES).document(inviteCode).get())
          .getString("householdId")

  private class ConstantRandom : Random() {
    override fun nextBits(bitCount: Int): Int = 0
  }

  private companion object {
    const val UNAVAILABLE_FIRESTORE_PORT = 18080
    const val UNAVAILABLE_APP_NAME = "unavailable-firestore-test"
  }
}
