// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: OpenAI Codex
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.chat.FirebaseChatRepository
import com.android.sharemate.model.chat.MessageType
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.model.user.ExpirationPreferences
import com.android.sharemate.model.user.FirebaseExpirationPreferencesRepository
import com.android.sharemate.model.user.FirebasePseudoRepository
import com.android.sharemate.utils.FirebaseEmulator
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import java.util.Date
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Checks that `firestore.rules` rejects what the app must never be able to do. */
@RunWith(AndroidJUnit4::class)
class FirestoreSecurityRulesTest {
  private val firestore = FirebaseEmulator.firestore
  private val repository = HouseholdRepositoryFirestore(firestore)

  @Before
  fun setUp() {
    FirebaseEmulator.clear()
  }

  @After
  fun tearDown() {
    FirebaseEmulator.clear()
  }

  @Test
  fun signedOutUserCannotReadOrWriteAnything() {
    val household = createHouseholdAs("creator")
    FirebaseEmulator.signOut()

    assertDenied(householdReference(household.id).get())
    assertDenied(inviteCodeReference(household.inviteCode).get())
    assertDenied(userReference(household.createdBy).get())
    assertDenied(userReference("anyone").set(mapOf("displayName" to "Anyone")))
    assertDenied(
        householdReference(household.id).update("memberIds", FieldValue.arrayUnion("anyone")))
  }

  @Test
  fun userCannotReadOrWriteAnotherUsersDocument() {
    val household = createHouseholdAs("creator")
    FirebaseEmulator.signInAs("outsider")

    assertDenied(userReference(household.createdBy).get())
    assertDenied(userReference(household.createdBy).set(mapOf("householdId" to "stolen")))
    assertDenied(firestore.collection(FirestoreCollections.USERS).get())
  }

  @Test
  fun userCanSaveExpiryPreferencesOnTheBoundariesOfTheRanges() = runBlocking {
    val userId = FirebaseEmulator.signInAs("alice")
    val repository = FirebaseExpirationPreferencesRepository(firestore)
    val soon = ExpirationPreferences.SOON_DAYS_RANGE
    val repeat = ExpirationPreferences.REPEAT_DAYS_RANGE
    val accepted =
        listOf(
            ExpirationPreferences(soon.first, repeat.first, repeat.first),
            ExpirationPreferences(soon.last, repeat.last, repeat.last),
            ExpirationPreferences(soon.first, soonRepeatDays = null, expiredRepeatDays = null))

    accepted.forEach {
      repository.save(userId, it)

      assertEquals(it, repository.get(userId))
    }
  }

  @Test
  fun userCanSaveOneExpiryPreferenceAtATime() {
    val userId = FirebaseEmulator.signInAs("alice")

    Tasks.await(expiryPreferencesWrite(userId, mapOf(UserExpirationFields.SOON_DAYS to 7)))
    Tasks.await(
        expiryPreferencesWrite(userId, mapOf(UserExpirationFields.EXPIRED_REPEAT_DAYS to 0)))

    val saved = Tasks.await(userReference(userId).get(Source.SERVER))
    assertEquals(
        mapOf(UserExpirationFields.SOON_DAYS to 7L, UserExpirationFields.EXPIRED_REPEAT_DAYS to 0L),
        saved.get(UserExpirationFields.PREFERENCES))
  }

  @Test
  fun userCannotSaveExpiryPreferencesOutsideTheAllowedRanges() {
    val userId = FirebaseEmulator.signInAs("alice")
    val soon = ExpirationPreferences.SOON_DAYS_RANGE
    val repeat = ExpirationPreferences.REPEAT_DAYS_RANGE
    val refused =
        listOf(
            mapOf(UserExpirationFields.SOON_DAYS to soon.first - 1),
            mapOf(UserExpirationFields.SOON_DAYS to soon.last + 1),
            mapOf(UserExpirationFields.SOON_REPEAT_DAYS to -1),
            mapOf(UserExpirationFields.SOON_REPEAT_DAYS to repeat.last + 1),
            mapOf(UserExpirationFields.EXPIRED_REPEAT_DAYS to repeat.last + 1),
            mapOf(UserExpirationFields.SOON_DAYS to "3"),
            mapOf(UserExpirationFields.SOON_DAYS to 2.5),
            mapOf(UserExpirationFields.SOON_DAYS to 3, "unknownField" to 1))

    refused.forEach { assertDenied(expiryPreferencesWrite(userId, it)) }
    assertDenied(expiryPreferencesWrite(userId, "not a map"))
  }

  @Test
  fun refusedExpiryPreferencesLeaveTheSavedOnesUntouched() = runBlocking {
    val userId = FirebaseEmulator.signInAs("alice")
    val repository = FirebaseExpirationPreferencesRepository(firestore)
    val saved = ExpirationPreferences(soonDays = 10, soonRepeatDays = 2, expiredRepeatDays = 5)
    repository.save(userId, saved)

    assertDenied(expiryPreferencesWrite(userId, mapOf(UserExpirationFields.SOON_DAYS to 1000)))

    assertEquals(saved, repository.get(userId))
  }

  @Test
  fun userCanStillUpdateOtherProfileFieldsAlongsideSavedExpiryPreferences() = runBlocking {
    val userId = FirebaseEmulator.signInAs("alice")
    FirebaseExpirationPreferencesRepository(firestore).save(userId, ExpirationPreferences())

    Tasks.await(userReference(userId).set(mapOf("householdId" to "home"), SetOptions.merge()))

    assertEquals(
        "home", Tasks.await(userReference(userId).get(Source.SERVER)).getString("householdId"))
  }

  @Test
  fun userCannotSaveExpiryPreferencesIntoAnotherUsersDocument() {
    val otherId = FirebaseEmulator.signInAs("other")
    FirebaseEmulator.signInAs("alice")

    assertDenied(expiryPreferencesWrite(otherId, mapOf(UserExpirationFields.SOON_DAYS to 7)))
  }

  @Test
  fun householdMemberCanReadPublicNameButNotPrivateUser() {
    val household = createHouseholdAs("creator")
    val pseudoRepository = FirebasePseudoRepository(firestore)
    runBlocking { pseudoRepository.setPseudo(household.createdBy, "Alice") }
    val memberId = FirebaseEmulator.signInAs("member")
    runBlocking { repository.joinHousehold(household.inviteCode, memberId) }

    assertEquals(
        "Alice",
        Tasks.await(
                firestore
                    .collection(FirestoreCollections.PROFILES)
                    .document(household.createdBy)
                    .get())
            .getString("displayName"))
    assertDenied(userReference(household.createdBy).get())
    assertDenied(userReference(household.createdBy).set(mapOf("pseudo" to "hijacked")))
    assertDenied(firestore.collection(FirestoreCollections.USERS).get())
    assertDenied(firestore.collection(FirestoreCollections.PROFILES).get())
    assertDenied(
        firestore
            .collection(FirestoreCollections.PROFILES)
            .document(household.createdBy)
            .set(mapOf("displayName" to "hijacked")))

    FirebaseEmulator.signInAs("outsider")
    assertDenied(
        firestore.collection(FirestoreCollections.PROFILES).document(household.createdBy).get())
  }

  @Test
  fun pseudoWritesPreserveOtherPublicFieldsAndRejectExtraFields() {
    val userId = FirebaseEmulator.signInAs("creator")
    val pseudo = firestore.collection(FirestoreCollections.PROFILES).document(userId)
    Tasks.await(pseudo.set(mapOf("email" to "creator@example.com", "photoUrl" to "photo")))
    runBlocking { FirebasePseudoRepository(firestore).setPseudo(userId, "Alice") }

    val profile = Tasks.await(pseudo.get())
    assertEquals("Alice", profile.getString("displayName"))
    assertEquals("creator@example.com", profile.getString("email"))
    assertEquals("photo", profile.getString("photoUrl"))
    assertFalse(Tasks.await(userReference(userId).get()).contains("pseudo"))
    assertDenied(pseudo.set(mapOf("displayName" to "Alice", "private" to "secret")))
    assertDenied(firestore.collection("userPseudos").document(userId).get())
  }

  @Test
  fun nonMemberCannotReadHousehold() {
    val household = createHouseholdAs("creator")
    FirebaseEmulator.signInAs("outsider")

    assertDenied(householdReference(household.id).get())
    assertDenied(
        firestore
            .collection(FirestoreCollections.HOUSEHOLDS)
            .whereArrayContains("memberIds", household.createdBy)
            .get())
  }

  @Test
  fun householdsCannotBeListedWithoutFilteringOnOwnMembership() {
    createHouseholdAs("creator")

    assertDenied(firestore.collection(FirestoreCollections.HOUSEHOLDS).get())
  }

  @Test
  fun staleAvailabilityCheckCannotOverwriteAClaimedCodeOrPartiallyCreateAFridge() {
    val owner = FirebaseEmulator.signInAs("creator")
    val first = Household("first", "First", "ABCDEF", listOf(owner), owner, Date())
    val second = first.copy(id = "second", name = "Second")
    // Both attempts observe availability before the first attempt commits.
    assertFalse(Tasks.await(inviteCodeReference(first.inviteCode).get()).exists())
    assertFalse(Tasks.await(inviteCodeReference(second.inviteCode).get()).exists())
    Tasks.await(createHousehold(first))

    assertDenied(createHousehold(second))

    assertFalse(Tasks.await(householdReference(second.id).get()).exists())
    assertEquals(
        first, Tasks.await(householdReference(first.id).get()).toObject(Household::class.java))
    assertEquals(
        first.id, Tasks.await(inviteCodeReference(first.inviteCode).get()).getString("householdId"))
    assertEquals(first.id, Tasks.await(userReference(owner).get()).getString("householdId"))
  }

  @Test
  fun inviteCodesCannotBeListedUpdatedOrDeleted() {
    val household = createHouseholdAs("creator")

    assertDenied(firestore.collection(FirestoreCollections.INVITE_CODES).get())
    assertDenied(inviteCodeReference(household.inviteCode).update("householdId", "elsewhere"))
    assertDenied(inviteCodeReference(household.inviteCode).delete())
  }

  @Test
  fun inviteCodeCannotBeCreatedForAnotherUsersHousehold() {
    val household = createHouseholdAs("creator")
    FirebaseEmulator.signInAs("outsider")

    assertDenied(inviteCodeReference("ZZZZZZ").set(mapOf("householdId" to household.id)))
  }

  @Test
  fun householdCanOnlyBeCreatedByItsSoleInitialMember() {
    val userId = FirebaseEmulator.signInAs("creator")
    val valid =
        Household(
            id = "household",
            name = "Home",
            inviteCode = "ABCDEF",
            memberIds = listOf(userId),
            createdBy = userId,
            createdAt = Date())

    assertDenied(createHousehold(valid.copy(createdBy = "someone-else")))
    assertDenied(createHousehold(valid.copy(memberIds = listOf(userId, "someone-else"))))
    assertDenied(createHousehold(valid.copy(memberIds = listOf("someone-else"))))
    assertDenied(createHousehold(valid.copy(name = "")))
    // Without its invite-code lookup document, the household could never be joined.
    assertDenied(householdReference(valid.id).set(valid))

    Tasks.await(createHousehold(valid))
  }

  @Test
  fun explicitFridgeTypesArePersistedAndReadByRepository() = runBlocking {
    val userId = FirebaseEmulator.signInAs("creator")
    for (type in FridgeType.entries) {
      val household =
          Household(
              id = type.name,
              name = "Home",
              inviteCode = if (type == FridgeType.PRIVATE) "" else type.name,
              memberIds = listOf(userId),
              createdBy = userId,
              createdAt = Date(),
              type = type)

      Tasks.await(createHousehold(household))

      assertEquals(type.name, Tasks.await(householdReference(household.id).get()).getString("type"))
      assertEquals(household, repository.getHousehold(household.id))
      assertDenied(householdReference(household.id).update("type", "INVALID"))
      assertDenied(
          householdReference(household.id)
              .update("type", if (type == FridgeType.PRIVATE) "SHARED" else "PRIVATE"))
    }
  }

  @Test
  fun householdCreationRejectsMalformedFridgeTypes() {
    val userId = FirebaseEmulator.signInAs("creator")
    val household = Household("invalid", "Home", "ABCDEF", listOf(userId), userId, Date())
    val data =
        mapOf(
            "id" to household.id,
            "name" to household.name,
            "inviteCode" to household.inviteCode,
            "memberIds" to household.memberIds,
            "createdBy" to household.createdBy,
            "createdAt" to household.createdAt)

    for (invalidType in listOf(null, "", "private", "UNKNOWN", 1L, listOf("SHARED"))) {
      assertDenied(createHousehold(household, data + ("type" to invalidType)))
    }
  }

  @Test
  fun userCannotAddOrRemoveOtherHouseholdMembers() {
    val household = createHouseholdAs("creator")
    val memberId = FirebaseEmulator.signInAs("member")
    runBlocking { repository.joinHousehold(household.inviteCode, memberId) }

    assertDenied(
        householdReference(household.id).update("memberIds", FieldValue.arrayUnion("someone-else")))
    assertDenied(
        householdReference(household.id)
            .update("memberIds", FieldValue.arrayRemove(household.createdBy)))
    assertDenied(householdReference(household.id).update("memberIds", listOf(memberId)))
  }

  @Test
  fun leavingRequiresUnlinkingOwnProfile() {
    val household = createHouseholdAs("creator")
    val memberId = FirebaseEmulator.signInAs("member")
    runBlocking { repository.joinHousehold(household.inviteCode, memberId) }

    // The member's profile still points at the household, so this half of a leave is rejected.
    assertDenied(
        householdReference(household.id).update("memberIds", FieldValue.arrayRemove(memberId)))
  }

  @Test
  fun leavingCannotPointProfileAtAnotherHousehold() {
    val household = createHouseholdAs("creator")
    val memberId = FirebaseEmulator.signInAs("member")
    runBlocking { repository.joinHousehold(household.inviteCode, memberId) }

    assertDenied(
        firestore
            .batch()
            .update(householdReference(household.id), "memberIds", FieldValue.arrayRemove(memberId))
            .set(
                userReference(memberId),
                mapOf("householdId" to "another-household"),
                SetOptions.merge())
            .commit())
  }

  @Test
  fun memberCannotRemoveAnotherMemberWhileUnlinkingOwnProfile() {
    val household = createHouseholdAs("creator")
    val memberId = FirebaseEmulator.signInAs("member")
    runBlocking { repository.joinHousehold(household.inviteCode, memberId) }

    assertDenied(
        firestore
            .batch()
            .update(
                householdReference(household.id),
                "memberIds",
                FieldValue.arrayRemove(household.createdBy))
            .set(
                userReference(memberId),
                mapOf("householdId" to FieldValue.delete()),
                SetOptions.merge())
            .commit())
  }

  @Test
  fun leavingCannotChangeOtherHouseholdFields() {
    val household = createHouseholdAs("creator")
    val memberId = FirebaseEmulator.signInAs("member")
    runBlocking { repository.joinHousehold(household.inviteCode, memberId) }

    assertDenied(
        firestore
            .batch()
            .update(
                householdReference(household.id),
                mapOf("memberIds" to FieldValue.arrayRemove(memberId), "name" to "Renamed"))
            .set(
                userReference(memberId),
                mapOf("householdId" to FieldValue.delete()),
                SetOptions.merge())
            .commit())
  }

  @Test
  fun joiningRequiresPointingOwnProfileAtTheHousehold() {
    val household = createHouseholdAs("creator")
    val outsiderId = FirebaseEmulator.signInAs("outsider")

    assertDenied(
        householdReference(household.id).update("memberIds", FieldValue.arrayUnion(outsiderId)))
  }

  @Test
  fun joiningWithoutTheHouseholdsInviteCodeIsDenied() {
    val household = createHouseholdAs("creator")
    val otherCode = runBlocking {
      val otherCreator = FirebaseEmulator.signInAs("other-creator")
      repository.createHousehold("Elsewhere", otherCreator).inviteCode
    }
    val outsiderId = FirebaseEmulator.signInAs("outsider")

    // An outsider who knows the household ID forges the app's join batch with no code, a wrong
    // code, or another household's valid code.
    for (profile in
        listOf(
            mapOf("householdId" to household.id),
            mapOf("householdId" to household.id, "joinCode" to "WRONG1"),
            mapOf("householdId" to household.id, "joinCode" to otherCode))) {
      assertDenied(
          firestore
              .batch()
              .update(
                  householdReference(household.id), "memberIds", FieldValue.arrayUnion(outsiderId))
              .set(userReference(outsiderId), profile, SetOptions.merge())
              .commit())
    }

    assertDenied(householdReference(household.id).get(Source.SERVER))
    FirebaseEmulator.signInAs("creator")
    assertEquals(
        listOf(household.createdBy),
        Tasks.await(householdReference(household.id).get()).get("memberIds"))
  }

  @Test
  fun joiningWithTheInviteCodeIsAllowed() {
    val household = createHouseholdAs("creator")
    val memberId = FirebaseEmulator.signInAs("member")

    val joined = runBlocking { repository.joinHousehold(household.inviteCode, memberId) }

    assertEquals(listOf(household.createdBy, memberId), joined.memberIds)
  }

  @Test
  fun memberCannotChangeOtherHouseholdFieldsOrDeleteHousehold() {
    val household = createHouseholdAs("creator")

    assertDenied(householdReference(household.id).update("name", "Renamed"))
    assertDenied(householdReference(household.id).update("inviteCode", "ZZZZZZ"))
    assertDenied(householdReference(household.id).update("createdBy", "someone-else"))
    assertDenied(householdReference(household.id).delete())
  }

  // ---------------------------------------------------------------------------
  // Items: every item belongs to a household and is PERSONAL (owner only) or SHARED (every
  // current member). Access follows the household's current memberIds, even for the owner.
  // ---------------------------------------------------------------------------

  @Test
  fun memberCannotReadOrChangeAnotherMembersPersonalItem() {
    val household = createHouseholdAs("creator")
    val creatorId = FirebaseEmulator.auth.uid!!
    val itemRef = itemReference("item1")
    Tasks.await(itemRef.set(itemData(creatorId, household.id, "PERSONAL")))
    assertEquals("Apple", Tasks.await(itemRef.get()).getString("name"))

    val memberId = FirebaseEmulator.signInAs("member")
    runBlocking { repository.joinHousehold(household.inviteCode, memberId) }
    assertDenied(itemRef.get())
    assertDenied(itemRef.update("name", "Banana"))
    assertDenied(itemRef.update("sharing", "SHARED"))
    assertDenied(itemRef.delete())
    // A query that could return personal items of others is rejected as a whole.
    assertDenied(itemsOf(household.id).get())
    assertDenied(itemsOf(household.id).whereEqualTo("ownerId", creatorId).get())

    FirebaseEmulator.signInAs("outsider")
    assertDenied(itemRef.get())
    assertDenied(itemRef.update("name", "Banana"))
    assertDenied(itemRef.delete())
    assertDenied(itemsOf(household.id).whereEqualTo("sharing", "SHARED").get())
  }

  @Test
  fun membersSeeSharedItemsAndOwnItemsThroughScopedQueries() {
    val household = createHouseholdAs("creator")
    val creatorId = FirebaseEmulator.auth.uid!!
    Tasks.await(itemReference("personal").set(itemData(creatorId, household.id, "PERSONAL")))
    Tasks.await(itemReference("shared").set(itemData(creatorId, household.id, "SHARED")))

    val memberId = FirebaseEmulator.signInAs("member")
    runBlocking { repository.joinHousehold(household.inviteCode, memberId) }
    Tasks.await(itemReference("mine").set(itemData(memberId, household.id, "PERSONAL")))
    assertEquals("Apple", Tasks.await(itemReference("shared").get()).getString("name"))
    assertEquals(
        listOf("shared"),
        Tasks.await(itemsOf(household.id).whereEqualTo("sharing", "SHARED").get()).documents.map {
          it.id
        })
    assertEquals(
        listOf("mine"),
        Tasks.await(itemsOf(household.id).whereEqualTo("ownerId", memberId).get()).documents.map {
          it.id
        })
    // Members edit and delete shared items, never the personal items of others.
    Tasks.await(itemReference("shared").update("status", "EATEN"))
    assertDenied(itemReference("personal").update("status", "EATEN"))
    Tasks.await(itemReference("shared").delete())
    assertDenied(itemReference("personal").delete())
  }

  @Test
  fun userCannotChangeOwnerOfItem() {
    val household = createHouseholdAs("creator")
    val creatorId = FirebaseEmulator.auth.uid!!
    val itemRef = itemReference("item1")
    Tasks.await(itemRef.set(itemData(creatorId, household.id, "SHARED")))

    assertDenied(itemRef.update("ownerId", "someone-else"))
  }

  @Test
  fun memberCannotChangeOwnerSharingOrHouseholdOfSomeoneElsesSharedItem() {
    val household = createHouseholdAs("creator")
    val creatorId = FirebaseEmulator.auth.uid!!
    val memberId = FirebaseEmulator.signInAs("member")
    runBlocking { repository.joinHousehold(household.inviteCode, memberId) }

    FirebaseEmulator.signInAs("creator")
    val itemRef = itemReference("item1")
    Tasks.await(itemRef.set(itemData(creatorId, household.id, "SHARED")))

    FirebaseEmulator.signInAs("member")
    // A member can edit the item's content, but cannot unshare it, move it nor take it over.
    Tasks.await(itemRef.update("name", "Banana"))
    assertDenied(itemRef.update("sharing", "PERSONAL"))
    assertDenied(itemRef.update("householdId", null))
    assertDenied(itemRef.update("ownerId", memberId))
  }

  @Test
  fun ownerCanShareAndUnshareOwnItemButNeverMoveIt() {
    val household = createHouseholdAs("creator")
    val creatorId = FirebaseEmulator.auth.uid!!
    val otherHousehold = createHouseholdAs("creator")
    val itemRef = itemReference("item1")
    Tasks.await(itemRef.set(itemData(creatorId, household.id, "PERSONAL")))

    Tasks.await(itemRef.update("sharing", "SHARED"))
    Tasks.await(itemRef.update("sharing", "PERSONAL"))
    assertDenied(itemRef.update("sharing", "SOMETHING_ELSE"))
    assertDenied(itemRef.update("householdId", null))
    assertDenied(itemRef.update("householdId", ""))
    assertDenied(itemRef.update("householdId", otherHousehold.id))
  }

  @Test
  fun userCannotAddItemsToAHouseholdTheyAreNotIn() {
    val otherHousehold = createHouseholdAs("other")
    val ownerId = FirebaseEmulator.signInAs("owner")
    val ownHousehold = runBlocking { repository.createHousehold("Mine", ownerId) }
    val itemRef = itemReference("item1")

    // Creating directly in a foreign household is denied, whoever the owner is...
    for (sharing in listOf("PERSONAL", "SHARED")) {
      assertDenied(itemRef.set(itemData(ownerId, otherHousehold.id, sharing)))
    }
    assertDenied(itemRef.set(itemData(otherHousehold.createdBy, otherHousehold.id, "SHARED")))
    // ...and so is moving an existing item into it.
    Tasks.await(itemRef.set(itemData(ownerId, ownHousehold.id, "PERSONAL")))
    assertDenied(itemRef.update("householdId", otherHousehold.id))
  }

  @Test
  fun itemsMustBelongToAnExistingHousehold() {
    val ownerId = FirebaseEmulator.signInAs("owner")
    runBlocking { repository.createHousehold("Mine", ownerId) }
    val itemRef = itemReference("item1")

    assertDenied(itemRef.set(itemData(ownerId, null, "PERSONAL")))
    assertDenied(itemRef.set(itemData(ownerId, "", "PERSONAL")))
    assertDenied(itemRef.set(itemData(ownerId, "no-such-household", "PERSONAL")))
  }

  @Test
  fun leavingAHouseholdRevokesAccessToItsItemsWhichStayInPlace() {
    val household = createHouseholdAs("creator")
    val memberId = FirebaseEmulator.signInAs("member")
    runBlocking { repository.joinHousehold(household.inviteCode, memberId) }
    Tasks.await(itemReference("personal").set(itemData(memberId, household.id, "PERSONAL")))
    Tasks.await(itemReference("shared").set(itemData(memberId, household.id, "SHARED")))

    runBlocking { repository.leaveHousehold(memberId) }

    // Not even their own items: no read, query, write or delete, nor a new item.
    for (id in listOf("personal", "shared")) {
      assertDenied(itemReference(id).get())
      assertDenied(itemReference(id).update("name", "Banana"))
      assertDenied(itemReference(id).set(itemData(memberId, household.id, "SHARED")))
      assertDenied(itemReference(id).delete())
    }
    assertDenied(itemsOf(household.id).whereEqualTo("ownerId", memberId).get())
    assertDenied(itemsOf(household.id).whereEqualTo("sharing", "SHARED").get())
    assertDenied(itemReference("new").set(itemData(memberId, household.id, "PERSONAL")))
    // A stale profile pointing at the household grants nothing: memberIds is the source of truth.
    Tasks.await(userReference(memberId).set(mapOf("householdId" to household.id)))
    assertDenied(itemReference("personal").get())
    assertDenied(itemsOf(household.id).whereEqualTo("ownerId", memberId).get())

    // The remaining member still sees the shared item, but not the leaver's personal one.
    FirebaseEmulator.signInAs("creator")
    assertEquals(
        listOf("shared"),
        Tasks.await(itemsOf(household.id).whereEqualTo("sharing", "SHARED").get()).documents.map {
          it.id
        })
    assertDenied(itemReference("personal").get())

    // Nothing was deleted: joining again gives the leaver their items back.
    FirebaseEmulator.signInAs("member")
    runBlocking { repository.joinHousehold(household.inviteCode, memberId) }
    assertEquals("Apple", Tasks.await(itemReference("personal").get()).getString("name"))
  }

  // ---------------------------------------------------------------------------
  // NEW: receipts
  // ---------------------------------------------------------------------------
  @Test
  fun userCannotChangeOwnerOfReceipt() {
    val ownerId = FirebaseEmulator.signInAs("owner")
    val receiptRef = firestore.collection(FirestoreCollections.RECEIPTS).document("receipt1")
    Tasks.await(receiptRef.set(receiptData(ownerId)))

    assertDenied(receiptRef.update("ownerId", "someone-else"))
  }

  @Test
  fun householdMemberCanReadAndUpdateButNotDeleteSharedReceipt() {
    val household = createHouseholdAs("creator")
    val creatorId = FirebaseEmulator.auth.uid!!
    val memberId = FirebaseEmulator.signInAs("member")
    runBlocking { repository.joinHousehold(household.inviteCode, memberId) }

    FirebaseEmulator.signInAs("creator")
    val receiptRef = firestore.collection(FirestoreCollections.RECEIPTS).document("receipt1")
    Tasks.await(receiptRef.set(receiptData(creatorId, household.id)))

    FirebaseEmulator.signInAs("member")
    Tasks.await(receiptRef.get())
    Tasks.await(receiptRef.update("storeName", "Coop"))
    assertDenied(receiptRef.delete())

    // The owner can still delete it.
    FirebaseEmulator.signInAs("creator")
    Tasks.await(receiptRef.delete())
  }

  @Test
  fun userCannotCreateReceiptInHouseholdTheyAreNotIn() {
    val otherHousehold = createHouseholdAs("other")
    val ownerId = FirebaseEmulator.signInAs("owner")
    val receiptRef = firestore.collection(FirestoreCollections.RECEIPTS).document("receipt1")

    assertDenied(receiptRef.set(receiptData(ownerId, otherHousehold.id)))
  }

  @Test
  fun memberCannotChangeHouseholdOfSomeoneElsesReceipt() {
    val household = createHouseholdAs("creator")
    val creatorId = FirebaseEmulator.auth.uid!!
    val memberId = FirebaseEmulator.signInAs("member")
    runBlocking { repository.joinHousehold(household.inviteCode, memberId) }

    FirebaseEmulator.signInAs("creator")
    val receiptRef = firestore.collection(FirestoreCollections.RECEIPTS).document("receipt1")
    Tasks.await(receiptRef.set(receiptData(creatorId, household.id)))

    FirebaseEmulator.signInAs("member")
    assertDenied(receiptRef.update("householdId", null))
  }

  @Test
  fun memberCanPostAndReadMessagesButNotEditOrDeleteThem() {
    val household = createHouseholdAs("creator")
    val memberId = FirebaseEmulator.signInAs("member")
    runBlocking { repository.joinHousehold(household.inviteCode, memberId) }
    val messageRef = messageReference(household.id, "m1")

    Tasks.await(messageRef.set(messageData("m1", memberId)))

    FirebaseEmulator.signInAs("creator")
    assertEquals("hello", Tasks.await(messageRef.get()).getString("text"))
    assertDenied(messageRef.update("text", "edited"))
    assertDenied(messageRef.delete())
  }

  @Test
  fun chatRepositoryWritesTextAndActivitiesUnderRules() = runBlocking {
    val household = createHouseholdAs("creator")
    val senderId = checkNotNull(FirebaseEmulator.auth.uid)
    val chat = FirebaseChatRepository(firestore)

    val textId = chat.sendMessage(household.id, senderId, "Sender", " hello ")
    assertNotNull(textId)
    assertEquals(
        "hello", Tasks.await(messageReference(household.id, textId!!).get()).getString("text"))

    for (type in listOf(MessageType.EATEN, MessageType.THROWN)) {
      val activityId =
          chat.sendActivity(
              household.id, senderId, "Sender", type, " Milk ", "https://example.com/image")
      assertNotNull(activityId)
      val stored = Tasks.await(messageReference(household.id, activityId!!).get())
      assertEquals(type.name, stored.getString("type"))
      assertEquals("Milk", stored.getString("itemName"))
    }

    assertNotNull(chat.sendActivity(household.id, senderId, "Sender", MessageType.EATEN, "Milk"))
  }

  @Test
  fun invalidTypedMessagesAreDenied() {
    val household = createHouseholdAs("creator")
    val senderId = checkNotNull(FirebaseEmulator.auth.uid)
    val base = messageData("bad", senderId)
    val ref = messageReference(household.id, "bad")

    assertDenied(ref.set(base + ("type" to "UNKNOWN")))
    assertDenied(
        ref.set(
            messageData("bad", "someone-else", text = "") +
                mapOf("type" to "THROWN", "itemName" to "Milk")))
    assertDenied(ref.set(base + ("type" to "TEXT") + ("itemName" to "Milk")))
    assertDenied(ref.set(base + ("type" to "EATEN") + ("itemName" to "Milk")))
    assertDenied(ref.set(base + mapOf("type" to "EATEN", "text" to "", "itemName" to " ")))
    assertDenied(ref.set(base + mapOf("type" to "THROWN", "text" to "", "itemName" to 123)))
    assertDenied(
        ref.set(
            base +
                mapOf(
                    "type" to "EATEN", "text" to "", "itemName" to "Milk", "itemImageUrl" to 123)))
    assertDenied(ref.set(base + mapOf("type" to "TEXT", "text" to "")))
  }

  @Test
  fun nonMemberCannotReadOrPostMessages() {
    val household = createHouseholdAs("creator")
    val outsiderId = FirebaseEmulator.signInAs("outsider")
    val messageRef = messageReference(household.id, "m1")

    assertDenied(messageRef.set(messageData("m1", outsiderId)))
    assertDenied(
        messageRef.set(
            messageData("m1", outsiderId, text = "") +
                mapOf("type" to "EATEN", "itemName" to "Milk")))
    assertDenied(messageReference(household.id, "m1").get())
  }

  @Test
  fun userCannotPostMessageAsSomeoneElseOrWithInvalidContent() {
    val household = createHouseholdAs("creator")
    val creatorId = FirebaseEmulator.auth.uid!!

    assertDenied(messageReference(household.id, "m1").set(messageData("m1", "someone-else")))
    assertDenied(messageReference(household.id, "m2").set(messageData("m2", creatorId, text = "")))
    assertDenied(
        messageReference(household.id, "m3").set(messageData("m3", creatorId, "x".repeat(2001))))
    assertDenied(
        messageReference(household.id, "m4").set(messageData("m4", creatorId) + ("extra" to 1)))
    assertDenied(messageReference(household.id, "m5").set(messageData("m4", creatorId)))
  }

  @Test
  fun userCannotPostWhitespaceOnlyMessageButCanPostMultilineMessage() {
    val household = createHouseholdAs("creator")
    val creatorId = FirebaseEmulator.auth.uid!!

    assertDenied(
        messageReference(household.id, "m1").set(messageData("m1", creatorId, text = "   ")))
    assertDenied(
        messageReference(household.id, "m2").set(messageData("m2", creatorId, text = "\t\t")))
    assertDenied(
        messageReference(household.id, "m3").set(messageData("m3", creatorId, text = "\n \t\r\n")))

    Tasks.await(
        messageReference(household.id, "m4")
            .set(messageData("m4", creatorId, text = "line one\n\tline two\n")))
  }

  private fun messageData(id: String, senderId: String, text: String = "hello"): Map<String, Any> =
      mapOf(
          "id" to id,
          "senderId" to senderId,
          "senderName" to "Sender",
          "text" to text,
          "timestamp" to Date())

  private fun messageReference(householdId: String, messageId: String): DocumentReference =
      householdReference(householdId).collection(FirestoreCollections.MESSAGES).document(messageId)

  /** A receipt that satisfies hasValidReceiptData; override fields as needed. */
  private fun receiptData(ownerId: String, householdId: String? = null): Map<String, Any?> =
      buildMap {
        put("ownerId", ownerId)
        put("storeName", "Migros")
        put("totalAmountCents", 1250L)
        if (householdId != null) put("householdId", householdId)
      }

  /** Creates a household through the repository, leaving its creator signed in. */
  private fun createHouseholdAs(alias: String): Household {
    val creatorId = FirebaseEmulator.signInAs(alias)
    return runBlocking { repository.createHousehold("Home", creatorId) }
  }

  /** Writes the same batch as the repository, for the signed-in user. */
  private fun createHousehold(household: Household, data: Any = household): Task<Void> {
    val batch = firestore.batch().set(householdReference(household.id), data)
    if (household.type == FridgeType.SHARED) {
      batch.set(inviteCodeReference(household.inviteCode), mapOf("householdId" to household.id))
    }
    return batch
        .set(
            userReference(checkNotNull(FirebaseEmulator.auth.uid)),
            mapOf("householdId" to household.id),
            SetOptions.merge())
        .commit()
  }

  /** An item document that satisfies hasValidItemData; householdId may be missing or invalid. */
  private fun itemData(ownerId: String, householdId: String?, sharing: String): Map<String, Any?> =
      buildMap {
        put("ownerId", ownerId)
        put("name", "Apple")
        put("quantity", 1)
        put("status", "ACTIVE")
        put("sharing", sharing)
        if (householdId != null) put("householdId", householdId)
      }

  private fun itemReference(itemId: String): DocumentReference =
      firestore.collection(FirestoreCollections.ITEMS).document(itemId)

  private fun itemsOf(householdId: String) =
      firestore.collection(FirestoreCollections.ITEMS).whereEqualTo("householdId", householdId)

  /** Merges [preferences], whatever its shape, into the expiry preferences of [userId]. */
  private fun expiryPreferencesWrite(userId: String, preferences: Any): Task<Void> =
      userReference(userId)
          .set(mapOf(UserExpirationFields.PREFERENCES to preferences), SetOptions.merge())

  private fun userReference(userId: String): DocumentReference =
      firestore.collection(FirestoreCollections.USERS).document(userId)

  private fun householdReference(householdId: String): DocumentReference =
      firestore.collection(FirestoreCollections.HOUSEHOLDS).document(householdId)

  private fun inviteCodeReference(inviteCode: String): DocumentReference =
      firestore.collection(FirestoreCollections.INVITE_CODES).document(inviteCode)

  private fun assertDenied(task: Task<*>) {
    val failure = assertThrows(ExecutionException::class.java) { Tasks.await(task) }
    assertEquals(
        FirebaseFirestoreException.Code.PERMISSION_DENIED,
        (failure.cause as? FirebaseFirestoreException)?.code)
  }
}
