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
import com.google.firebase.firestore.Query
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

  @Test
  fun signedOutUserCannotReadOrWriteItemsOrReceipts() {
    val ownerId = FirebaseEmulator.signInAs("owner")
    Tasks.await(itemReference("item1").set(itemData(ownerId)))
    Tasks.await(receiptReference("receipt1").set(receiptData(ownerId)))
    FirebaseEmulator.signOut()

    assertDenied(itemReference("item1").get())
    assertDenied(itemReference("item1").update("name", "Banana"))
    assertDenied(itemReference("item1").delete())
    assertDenied(itemReference("item2").set(itemData(ownerId)))
    assertDenied(receiptReference("receipt1").get())
    assertDenied(receiptReference("receipt1").update("storeName", "Coop"))
    assertDenied(receiptReference("receipt1").delete())
    assertDenied(receiptReference("receipt2").set(receiptData(ownerId)))
  }

  @Test
  fun itemRequiresStringOwnerAndStringOrNullHousehold() {
    val ownerId = FirebaseEmulator.signInAs("owner")
    val itemRef = itemReference("item1")

    assertDenied(itemRef.set(mapOf("name" to "Apple")))
    assertDenied(itemRef.set(mapOf("ownerId" to ownerId, "householdId" to 42)))

    Tasks.await(itemRef.set(itemData(ownerId)))
    assertDenied(itemRef.update("householdId", 42))
  }

  @Test
  fun receiptRequiresStringOwnerAndStringOrNullHousehold() {
    val ownerId = FirebaseEmulator.signInAs("owner")
    val receiptRef = receiptReference("receipt1")

    assertDenied(receiptRef.set(mapOf("storeName" to "Migros")))
    assertDenied(receiptRef.set(mapOf("ownerId" to ownerId, "householdId" to 42)))

    Tasks.await(receiptRef.set(receiptData(ownerId)))
    assertDenied(receiptRef.update("householdId", 42))
  }

  @Test
  fun memberOfAnotherHouseholdCannotAccessSharedItemOrReceipt() {
    val household = createHouseholdAs("creator")
    val creatorId = checkNotNull(FirebaseEmulator.auth.uid)
    Tasks.await(itemReference("item1").set(itemData(creatorId, household.id)))
    Tasks.await(receiptReference("receipt1").set(receiptData(creatorId, household.id)))

    // The neighbor belongs to a household, but not to the one the documents are shared with.
    createHouseholdAs("neighbor")

    assertDenied(itemReference("item1").get())
    assertDenied(itemReference("item1").update("name", "Banana"))
    assertDenied(itemReference("item1").delete())
    assertDenied(receiptReference("receipt1").get())
    assertDenied(receiptReference("receipt1").update("storeName", "Coop"))
    assertDenied(receiptReference("receipt1").delete())
  }

  @Test
  fun memberCannotCreateItemOrReceiptOwnedBySomeoneElse() {
    val household = createHouseholdAs("creator")
    val creatorId = checkNotNull(FirebaseEmulator.auth.uid)
    joinHouseholdAs("member", household)

    assertDenied(itemReference("item1").set(itemData(creatorId, household.id)))
    assertDenied(receiptReference("receipt1").set(receiptData(creatorId, household.id)))
  }

  @Test
  fun sharingAndUnsharingItemChangesWhatHouseholdMembersCanSee() {
    val household = createHouseholdAs("creator")
    val creatorId = checkNotNull(FirebaseEmulator.auth.uid)
    joinHouseholdAs("member", household)
    FirebaseEmulator.signInAs("creator")
    Tasks.await(itemReference("item1").set(itemData(creatorId)))

    FirebaseEmulator.signInAs("member")
    assertDenied(itemReference("item1").get())

    FirebaseEmulator.signInAs("creator")
    Tasks.await(itemReference("item1").update("householdId", household.id))
    FirebaseEmulator.signInAs("member")
    Tasks.await(itemReference("item1").get())

    FirebaseEmulator.signInAs("creator")
    Tasks.await(itemReference("item1").update("householdId", null))
    FirebaseEmulator.signInAs("member")
    assertDenied(itemReference("item1").get())
  }

  @Test
  fun ownerCanListOwnItemsAndReceipts() {
    val ownerId = FirebaseEmulator.signInAs("owner")
    Tasks.await(itemReference("item1").set(itemData(ownerId)))
    Tasks.await(receiptReference("receipt1").set(receiptData(ownerId)))
    val otherId = FirebaseEmulator.signInAs("other")
    Tasks.await(itemReference("item2").set(itemData(otherId)))
    Tasks.await(receiptReference("receipt2").set(receiptData(otherId)))

    FirebaseEmulator.signInAs("owner")
    val items = firestore.collection(FirestoreCollections.ITEMS)
    val receipts = firestore.collection(FirestoreCollections.RECEIPTS)

    assertEquals(setOf("item1"), documentIds(items.whereEqualTo("ownerId", ownerId)))
    assertEquals(setOf("receipt1"), documentIds(receipts.whereEqualTo("ownerId", ownerId)))
  }

  @Test
  fun memberCanListSharedItemsOnlyThroughHouseholdFilter() {
    val household = createHouseholdAs("creator")
    val creatorId = checkNotNull(FirebaseEmulator.auth.uid)
    Tasks.await(itemReference("private").set(itemData(creatorId)))
    Tasks.await(itemReference("shared").set(itemData(creatorId, household.id)))
    joinHouseholdAs("member", household)
    val items = firestore.collection(FirestoreCollections.ITEMS)

    assertEquals(setOf("shared"), documentIds(items.whereEqualTo("householdId", household.id)))
    // Filtering on the owner would also match the creator's private item.
    assertDenied(items.whereEqualTo("ownerId", creatorId).get())
    assertDenied(items.get())
  }

  @Test
  fun memberCanListSharedReceiptsOnlyThroughHouseholdFilter() {
    val household = createHouseholdAs("creator")
    val creatorId = checkNotNull(FirebaseEmulator.auth.uid)
    Tasks.await(receiptReference("private").set(receiptData(creatorId)))
    Tasks.await(receiptReference("shared").set(receiptData(creatorId, household.id)))
    joinHouseholdAs("member", household)
    val receipts = firestore.collection(FirestoreCollections.RECEIPTS)

    assertEquals(setOf("shared"), documentIds(receipts.whereEqualTo("householdId", household.id)))
    // Filtering on the owner would also match the creator's private receipt.
    assertDenied(receipts.whereEqualTo("ownerId", creatorId).get())
    assertDenied(receipts.get())
  }

  @Test
  fun nonMemberCannotListSharedItemsOrReceipts() {
    val household = createHouseholdAs("creator")
    val creatorId = checkNotNull(FirebaseEmulator.auth.uid)
    Tasks.await(itemReference("item1").set(itemData(creatorId, household.id)))
    Tasks.await(receiptReference("receipt1").set(receiptData(creatorId, household.id)))
    val items = firestore.collection(FirestoreCollections.ITEMS)
    val receipts = firestore.collection(FirestoreCollections.RECEIPTS)

    FirebaseEmulator.signInAs("outsider")
    assertDenied(items.whereEqualTo("householdId", household.id).get())
    assertDenied(items.whereEqualTo("ownerId", creatorId).get())
    assertDenied(receipts.whereEqualTo("householdId", household.id).get())
    assertDenied(receipts.whereEqualTo("ownerId", creatorId).get())
  }

  // ----- Helpers -------------------------------------------------------------

  /** Joins [household] as [alias] through the repository, leaving that user signed in. */
  private fun joinHouseholdAs(alias: String, household: Household) {
    val memberId = FirebaseEmulator.signInAs(alias)
    runBlocking { repository.joinHousehold(household.inviteCode, memberId) }
  }

  private fun itemReference(itemId: String): DocumentReference =
    firestore.collection(FirestoreCollections.ITEMS).document(itemId)

  private fun receiptReference(receiptId: String): DocumentReference =
    firestore.collection(FirestoreCollections.RECEIPTS).document(receiptId)

  private fun itemData(ownerId: String, householdId: String? = null): Map<String, Any?> =
    mapOf("ownerId" to ownerId, "householdId" to householdId, "name" to "Apple")

  private fun receiptData(ownerId: String, householdId: String? = null): Map<String, Any?> =
    mapOf("ownerId" to ownerId, "householdId" to householdId, "storeName" to "Migros")

  private fun documentIds(query: Query): Set<String> =
    Tasks.await(query.get()).documents.map { it.id }.toSet()

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
