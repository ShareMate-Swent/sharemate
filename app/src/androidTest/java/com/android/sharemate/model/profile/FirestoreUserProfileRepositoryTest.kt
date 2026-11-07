// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.profile

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.utils.FirebaseEmulator
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Checks public profiles against the Firestore emulator, including `firestore.rules`. */
@RunWith(AndroidJUnit4::class)
class FirestoreUserProfileRepositoryTest {
  private val firestore = FirebaseEmulator.firestore
  private val repository = FirestoreUserProfileRepository(firestore)
  private val households = HouseholdRepositoryFirestore(firestore)
  private var secondApp: FirebaseApp? = null

  @Before
  fun setUp() {
    FirebaseEmulator.clear()
  }

  @After
  fun tearDown() {
    secondApp?.delete()
    FirebaseEmulator.clear()
  }

  @Test
  fun membersReadEachOtherProfilesInMemberOrder() =
      runBlocking<Unit> {
        val creatorId = FirebaseEmulator.signInAs("creator")
        val household = households.createHousehold("Home", creatorId)
        repository.savePublicProfile(
            UserProfile(creatorId, "Creator", "https://example.com/c.png", "creator@example.com"))
        val memberId = FirebaseEmulator.signInAs("member")
        households.joinHousehold(household.inviteCode, memberId)
        repository.savePublicProfile(UserProfile(memberId, "Member", email = "member@example.com"))

        val members = withTimeout(TIMEOUT) { repository.observeMembers(household.id).first() }

        assertEquals(
            listOf(
                UserProfile(
                    creatorId, "Creator", "https://example.com/c.png", "creator@example.com"),
                UserProfile(memberId, "Member", email = "member@example.com")),
            members)
      }

  @Test
  fun memberWithoutSavedProfileHasOnlyTheirId() =
      runBlocking<Unit> {
        val creatorId = FirebaseEmulator.signInAs("creator")
        val household = households.createHousehold("Home", creatorId)

        val members = withTimeout(TIMEOUT) { repository.observeMembers(household.id).first() }

        assertEquals(listOf(UserProfile(creatorId)), members)
      }

  @Test
  fun savingAgainReplacesTheProfile() =
      runBlocking<Unit> {
        val creatorId = FirebaseEmulator.signInAs("creator")
        val household = households.createHousehold("Home", creatorId)
        repository.savePublicProfile(UserProfile(creatorId, "Old name", "https://example.com/old"))
        repository.savePublicProfile(UserProfile(creatorId, "New name"))

        val members = withTimeout(TIMEOUT) { repository.observeMembers(household.id).first() }

        assertEquals(listOf(UserProfile(creatorId, "New name")), members)
      }

  @Test
  fun membersUpdateWhenSomeoneJoinsAndLeaves() =
      runBlocking<Unit> {
        val creatorId = FirebaseEmulator.signInAs("creator")
        val household = households.createHousehold("Home", creatorId)
        val members = repository.observeMembers(household.id)
        // A second signed-in user, so the creator's listener keeps running as the creator.
        val other = signInOnSecondApp("member")
        val otherHouseholds = HouseholdRepositoryFirestore(other.firestore)

        otherHouseholds.joinHousehold(household.inviteCode, other.uid)
        withTimeout(TIMEOUT) {
          members.first { it.map(UserProfile::uid) == listOf(creatorId, other.uid) }
        }

        otherHouseholds.leaveHousehold(other.uid)
        withTimeout(TIMEOUT) { members.first { it.map(UserProfile::uid) == listOf(creatorId) } }
      }

  @Test
  fun missingHouseholdHasNoMembers() =
      runBlocking<Unit> {
        FirebaseEmulator.signInAs("creator")

        assertEquals(
            emptyList<UserProfile>(),
            withTimeout(TIMEOUT) { repository.observeMembers("missing").first() })
      }

  @Test
  fun userOutsideTheHouseholdCannotReadMemberProfiles() =
      runBlocking<Unit> {
        val household = createHouseholdWithProfile("creator")
        val outsiderId = FirebaseEmulator.signInAs("outsider")
        // Belonging to another household does not help.
        households.createHousehold("Elsewhere", outsiderId)

        assertDenied(profileReference(household.createdBy).get())
        val failure =
            assertThrows(FirebaseFirestoreException::class.java) {
              runBlocking {
                withTimeout(TIMEOUT) { repository.observeMembers(household.id).first() }
              }
            }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, failure.code)
      }

  @Test
  fun signedOutUserCannotReadProfiles() {
    val household = createHouseholdWithProfile("creator")
    FirebaseEmulator.signOut()

    assertDenied(profileReference(household.createdBy).get())
  }

  @Test
  fun profilesCannotBeListedEvenByMembers() {
    createHouseholdWithProfile("creator")

    assertDenied(firestore.collection(FirestoreCollections.PROFILES).get())
  }

  @Test
  fun userCannotWriteSomeoneElsesProfile() {
    val household = createHouseholdWithProfile("creator")
    FirebaseEmulator.signInAs("outsider")

    assertDenied(profileReference(household.createdBy).set(mapOf("displayName" to "Stolen")))
    assertDenied(profileReference(household.createdBy).delete())
  }

  @Test
  fun profileRejectsOtherFieldsAndSomeoneElsesEmail() {
    val userId = FirebaseEmulator.signInAs("creator")

    assertDenied(profileReference(userId).set(mapOf("householdId" to "anything")))
    assertDenied(profileReference(userId).set(mapOf("email" to "someone@example.com")))
    assertDenied(profileReference(userId).set(mapOf("displayName" to 42)))
    assertDenied(profileReference(userId).delete())
  }

  /** Creates a household whose creator saved a profile, leaving the creator signed in. */
  private fun createHouseholdWithProfile(alias: String): Household = runBlocking {
    val creatorId = FirebaseEmulator.signInAs(alias)
    repository.savePublicProfile(UserProfile(creatorId, alias, email = "$alias@example.com"))
    households.createHousehold("Home", creatorId)
  }

  private class SignedInApp(val uid: String, val firestore: FirebaseFirestore)

  /** Signs [alias] in on a separate Firebase app, so two users are signed in at once. */
  private fun signInOnSecondApp(alias: String): SignedInApp {
    val app =
        FirebaseApp.initializeApp(
            ApplicationProvider.getApplicationContext(),
            FirebaseApp.getInstance().options,
            "second-user")
    secondApp = app
    val auth =
        FirebaseAuth.getInstance(app).apply {
          useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.AUTH_PORT)
        }
    val firestore =
        FirebaseFirestore.getInstance(app).apply {
          useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.FIRESTORE_PORT)
        }
    val result =
        Tasks.await(auth.createUserWithEmailAndPassword("$alias@example.com", "emulator-password"))
    return SignedInApp(checkNotNull(result.user).uid, firestore)
  }

  private fun profileReference(userId: String): DocumentReference =
      firestore.collection(FirestoreCollections.PROFILES).document(userId)

  private fun assertDenied(task: Task<*>) {
    val failure = assertThrows(ExecutionException::class.java) { Tasks.await(task) }
    assertEquals(
        FirebaseFirestoreException.Code.PERMISSION_DENIED,
        (failure.cause as? FirebaseFirestoreException)?.code)
  }

  private companion object {
    const val TIMEOUT = 10_000L
  }
}
