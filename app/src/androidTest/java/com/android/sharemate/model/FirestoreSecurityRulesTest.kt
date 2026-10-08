// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.utils.FirebaseEmulator
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import java.util.Date
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
  fun joiningRequiresPointingOwnProfileAtTheHousehold() {
    val household = createHouseholdAs("creator")
    val outsiderId = FirebaseEmulator.signInAs("outsider")

    assertDenied(
        householdReference(household.id).update("memberIds", FieldValue.arrayUnion(outsiderId)))
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
  // FIXED: use the uid returned by signInAs instead of the literal "owner".
  // ---------------------------------------------------------------------------
  @Test
  fun userCannotReadOrWriteAnotherUsersPrivateItem() {
    val ownerId = FirebaseEmulator.signInAs("owner")
    val itemRef = firestore.collection(FirestoreCollections.ITEMS).document("item1")
    Tasks.await(itemRef.set(mapOf("ownerId" to ownerId, "name" to "Apple")))

    FirebaseEmulator.signInAs("outsider")
    assertDenied(itemRef.get())
    assertDenied(itemRef.update("name", "Banana"))
    assertDenied(itemRef.delete())
  }

  // ---------------------------------------------------------------------------
  // REPLACES userCannotChangeOwnerOrHouseholdOfItem
  // ---------------------------------------------------------------------------
  @Test
  fun userCannotChangeOwnerOfItem() {
    val household = createHouseholdAs("creator")
    val creatorId = FirebaseEmulator.auth.uid!!
    val itemRef = firestore.collection(FirestoreCollections.ITEMS).document("item1")
    Tasks.await(
        itemRef.set(
            mapOf("ownerId" to creatorId, "householdId" to household.id, "name" to "Apple")))

    assertDenied(itemRef.update("ownerId", "someone-else"))
  }

  @Test
  fun memberCannotChangeOwnerOrHouseholdOfSomeoneElsesItem() {
    val household = createHouseholdAs("creator")
    val creatorId = FirebaseEmulator.auth.uid!!
    val memberId = FirebaseEmulator.signInAs("member")
    runBlocking { repository.joinHousehold(household.inviteCode, memberId) }

    FirebaseEmulator.signInAs("creator")
    val itemRef = firestore.collection(FirestoreCollections.ITEMS).document("item1")
    Tasks.await(
        itemRef.set(
            mapOf("ownerId" to creatorId, "householdId" to household.id, "name" to "Apple")))

    FirebaseEmulator.signInAs("member")
    // A member can edit the item's content, but cannot unshare it nor take it over.
    Tasks.await(itemRef.update("name", "Banana"))
    assertDenied(itemRef.update("householdId", null))
    assertDenied(itemRef.update("ownerId", memberId))
  }

  @Test
  fun ownerCanShareAndUnshareOwnItem() {
    val household = createHouseholdAs("creator")
    val creatorId = FirebaseEmulator.auth.uid!!
    val itemRef = firestore.collection(FirestoreCollections.ITEMS).document("item1")
    Tasks.await(itemRef.set(mapOf("ownerId" to creatorId, "name" to "Apple")))

    Tasks.await(itemRef.update("householdId", household.id))
    Tasks.await(itemRef.update("householdId", null))
  }

  @Test
  fun userCannotShareItemWithHouseholdTheyAreNotIn() {
    val otherHousehold = createHouseholdAs("other")
    val ownerId = FirebaseEmulator.signInAs("owner")
    val itemRef = firestore.collection(FirestoreCollections.ITEMS).document("item1")

    // Creating directly in a foreign household is denied...
    assertDenied(
        itemRef.set(
            mapOf("ownerId" to ownerId, "householdId" to otherHousehold.id, "name" to "Apple")))

    // ...and so is moving an existing private item into it.
    Tasks.await(itemRef.set(mapOf("ownerId" to ownerId, "name" to "Apple")))
    assertDenied(itemRef.update("householdId", otherHousehold.id))
  }

  // ---------------------------------------------------------------------------
  // NEW: receipts
  // ---------------------------------------------------------------------------
  @Test
  fun userCannotChangeOwnerOfReceipt() {
    val ownerId = FirebaseEmulator.signInAs("owner")
    val receiptRef = firestore.collection(FirestoreCollections.RECEIPTS).document("receipt1")
    Tasks.await(receiptRef.set(mapOf("ownerId" to ownerId, "storeName" to "Migros")))

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
    Tasks.await(
        receiptRef.set(
            mapOf("ownerId" to creatorId, "householdId" to household.id, "storeName" to "Migros")))

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

    assertDenied(
        receiptRef.set(
            mapOf(
                "ownerId" to ownerId, "householdId" to otherHousehold.id, "storeName" to "Migros")))
  }

  @Test
  fun memberCannotChangeHouseholdOfSomeoneElsesReceipt() {
    val household = createHouseholdAs("creator")
    val creatorId = FirebaseEmulator.auth.uid!!
    val memberId = FirebaseEmulator.signInAs("member")
    runBlocking { repository.joinHousehold(household.inviteCode, memberId) }

    FirebaseEmulator.signInAs("creator")
    val receiptRef = firestore.collection(FirestoreCollections.RECEIPTS).document("receipt1")
    Tasks.await(
        receiptRef.set(
            mapOf("ownerId" to creatorId, "householdId" to household.id, "storeName" to "Migros")))

    FirebaseEmulator.signInAs("member")
    assertDenied(receiptRef.update("householdId", null))
  }

  /** Creates a household through the repository, leaving its creator signed in. */
  private fun createHouseholdAs(alias: String): Household {
    val creatorId = FirebaseEmulator.signInAs(alias)
    return runBlocking { repository.createHousehold("Home", creatorId) }
  }

  /** Writes the same batch as the repository, for the signed-in user. */
  private fun createHousehold(household: Household): Task<Void> =
      firestore
          .batch()
          .set(householdReference(household.id), household)
          .set(inviteCodeReference(household.inviteCode), mapOf("householdId" to household.id))
          .set(
              userReference(checkNotNull(FirebaseEmulator.auth.uid)),
              mapOf("householdId" to household.id),
              SetOptions.merge())
          .commit()

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
