// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.model.item.FirebaseItemRepository
import com.android.sharemate.model.item.Item
import com.android.sharemate.utils.FirebaseEmulator
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import java.util.Date
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Checks that `firestore.rules` rejects what the app must never be able to do. */
@RunWith(AndroidJUnit4::class)
class FirestoreSecurityRulesTest {
  private val firestore = FirebaseEmulator.firestore
  private val repository = HouseholdRepositoryFirestore(firestore)
  private val itemRepository = FirebaseItemRepository(firestore)

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
  fun userCanReadWriteAndDeleteOwnProfile() {
    val userId = FirebaseEmulator.signInAs("user")
    val profileRef = userReference(userId)

    Tasks.await(profileRef.set(mapOf("displayName" to "Alice")))
    assertEquals("Alice", Tasks.await(profileRef.get()).getString("displayName"))
    Tasks.await(profileRef.update("displayName", "Bob"))
    Tasks.await(profileRef.delete())
  }

  // ======================= invite codes =======================

  @Test
  fun signedInUserCanFetchInviteCodeByItsValue() {
    val household = createHouseholdAs("creator")
    FirebaseEmulator.signInAs("joiner")

    val lookup = Tasks.await(inviteCodeReference(household.inviteCode).get())

    assertEquals(household.id, lookup.getString("householdId"))
  }

  @Test
  fun inviteCodeMustMatchTheCodeStoredOnTheHousehold() {
    val household = createHouseholdAs("creator")
    val otherCode = "NOT-${household.inviteCode}"

    // The creator owns the household, but the code does not match household.inviteCode.
    assertDenied(inviteCodeReference(otherCode).set(mapOf("householdId" to household.id)))
  }

  @Test
  fun inviteCodeLookupMustBeWellFormedAndPointToTheNewHousehold() {
    val userId = FirebaseEmulator.signInAs("creator")
    val household = wellFormedHousehold(userId)

    assertDenied(commitHousehold(household, mapOf("householdId" to "another-household")))
    assertDenied(commitHousehold(household, mapOf("householdId" to 42)))
    assertDenied(commitHousehold(household, validLookup + ("extra" to "field")))
  }

  // ======================= households =======================

  @Test
  fun wellFormedHouseholdIsAccepted() {
    val userId = FirebaseEmulator.signInAs("creator")

    // Baseline for the negative test below: only the mutated field makes the write fail.
    Tasks.await(commitHousehold(wellFormedHousehold(userId), validLookup))
  }

  @Test
  fun householdCreationRejectsMalformedData() {
    val userId = FirebaseEmulator.signInAs("creator")
    val valid = wellFormedHousehold(userId)

    assertDenied(commitHousehold(valid + ("id" to "other-id"), validLookup))
    assertDenied(commitHousehold(valid + ("extra" to "field"), validLookup))
    assertDenied(commitHousehold(valid - "createdAt", validLookup))
    assertDenied(commitHousehold(valid + ("createdAt" to "yesterday"), validLookup))
    assertDenied(commitHousehold(valid + ("name" to 42), validLookup))
  }

  @Test
  fun fetchingMissingHouseholdReportsNotFoundInsteadOfDenying() {
    FirebaseEmulator.signInAs("user")

    val snapshot = Tasks.await(householdReference("does-not-exist").get())

    assertFalse(snapshot.exists())
  }

  @Test
  fun memberCanListOnlyTheirOwnHouseholds() {
    val own = createHouseholdAs("creator")
    createHouseholdAs("other")
    FirebaseEmulator.signInAs("creator")

    val result =
        Tasks.await(
            firestore
                .collection(FirestoreCollections.HOUSEHOLDS)
                .whereArrayContains("memberIds", own.createdBy)
                .get())

    assertEquals(listOf(own.id), result.documents.map { it.id })
  }

  @Test
  fun joinedMemberCanReadHouseholdWithBothMembers() {
    val household = createHouseholdAs("creator")
    val memberId = joinHouseholdAs("member", household)

    val snapshot = Tasks.await(householdReference(household.id).get())

    val memberIds = (snapshot.get("memberIds") as List<*>).toSet()
    assertEquals(setOf(household.createdBy, memberId), memberIds)
  }

  @Test
  fun existingMemberCanRepeatTheJoinWithoutDuplicatingMembership() {
    val household = createHouseholdAs("creator")
    val memberId = joinHouseholdAs("member", household)
    val householdRef = householdReference(household.id)

    Tasks.await(householdRef.update("memberIds", FieldValue.arrayUnion(memberId)))

    val memberIds = Tasks.await(householdRef.get()).get("memberIds") as List<*>
    assertEquals(2, memberIds.size)
  }

  @Test
  fun joiningWithProfilePointingAtAnotherHouseholdIsDenied() {
    val household = createHouseholdAs("creator")
    val outsiderId = FirebaseEmulator.signInAs("outsider")
    val householdRef = householdReference(household.id)

    val join =
        firestore
            .batch()
            .update(householdRef, "memberIds", FieldValue.arrayUnion(outsiderId))
            .set(userReference(outsiderId), mapOf("householdId" to "elsewhere"), SetOptions.merge())

    assertDenied(join.commit())
  }

  // ======================= items =======================

  @Test
  fun userCanReadWriteAndDeleteOwnPrivateItem() {
    val userId = FirebaseEmulator.signInAs("owner")
    val itemRef = itemReference("item1")

    Tasks.await(itemRef.set(mapOf("ownerId" to userId, "name" to "Apple")))
    assertEquals("Apple", Tasks.await(itemRef.get()).getString("name"))
    Tasks.await(itemRef.update("name", "Banana"))
    Tasks.await(itemRef.delete())
  }

  @Test
  fun userCannotCreateItemForAnotherUser() {
    FirebaseEmulator.signInAs("owner")

    assertDenied(itemReference("item1").set(mapOf("ownerId" to "someone-else", "name" to "Apple")))
  }

  @Test
  fun householdMemberCanReadWriteAndDeleteSharedItem() {
    val household = createHouseholdAs("creator")
    val memberId = joinHouseholdAs("member", household)
    val itemRef = itemReference("item1")

    Tasks.await(
        itemRef.set(mapOf("ownerId" to memberId, "householdId" to household.id, "name" to "Apple")))

    FirebaseEmulator.signInAs("creator")
    assertEquals("Apple", Tasks.await(itemRef.get()).getString("name"))
    Tasks.await(itemRef.update("name", "Banana"))
    Tasks.await(itemRef.delete())
  }

  @Test
  fun nonMemberCannotReadOrWriteSharedItem() {
    val household = createHouseholdAs("creator")
    val itemRef = itemReference("item1")
    Tasks.await(
        itemRef.set(
            mapOf(
                "ownerId" to household.createdBy,
                "householdId" to household.id,
                "name" to "Apple")))

    FirebaseEmulator.signInAs("outsider")
    assertDenied(itemRef.get())
    assertDenied(itemRef.update("name", "Banana"))
    assertDenied(itemRef.delete())
  }

  @Test
  fun itemWithMalformedOwnerOrHouseholdIsRejected() {
    val userId = FirebaseEmulator.signInAs("owner")
    val itemRef = itemReference("item1")

    assertDenied(itemRef.set(mapOf("ownerId" to userId, "householdId" to 42)))
    assertDenied(itemRef.set(mapOf("ownerId" to 42)))
    // An explicit null householdId is equivalent to a private item.
    Tasks.await(itemRef.set(mapOf("ownerId" to userId, "householdId" to null)))
  }

  @Test
  fun ownerCannotSetMalformedHouseholdOnUpdate() {
    val userId = FirebaseEmulator.signInAs("owner")
    val itemRef = itemReference("item1")
    Tasks.await(itemRef.set(mapOf("ownerId" to userId, "name" to "Apple")))

    assertDenied(itemRef.update("householdId", 42))
    assertDenied(itemRef.update("householdId", "missing-household"))
  }

  @Test
  fun signedOutUserCannotAccessItemsOrReceipts() {
    val userId = FirebaseEmulator.signInAs("owner")
    Tasks.await(itemReference("item1").set(mapOf("ownerId" to userId, "name" to "Apple")))
    Tasks.await(
        receiptReference("receipt1").set(mapOf("ownerId" to userId, "storeName" to "Migros")))
    FirebaseEmulator.signOut()

    assertDenied(itemReference("item1").get())
    assertDenied(itemReference("item1").delete())
    assertDenied(itemReference("item2").set(mapOf("ownerId" to userId)))
    assertDenied(receiptReference("receipt1").get())
    assertDenied(receiptReference("receipt1").delete())
    assertDenied(receiptReference("receipt2").set(mapOf("ownerId" to userId)))
  }

  // The following two tests exercise the rules through real queries. If one fails, the rule is
  // valid but cannot be proven safe by Firestore's query evaluator: that is a real limitation to
  // fix in firestore.rules, not a flaw in the test.

  @Test
  fun itemsCannotBeListedWithoutFiltering() {
    val userId = FirebaseEmulator.signInAs("owner")
    Tasks.await(itemReference("item1").set(mapOf("ownerId" to userId, "name" to "Apple")))

    assertDenied(firestore.collection(FirestoreCollections.ITEMS).get())
  }

  @Test
  fun userCanListOwnItemsButNotThoseOfAnotherUser() {
    val ownerId = FirebaseEmulator.signInAs("owner")
    Tasks.await(itemReference("item1").set(mapOf("ownerId" to ownerId, "name" to "Apple")))
    val ownItems = firestore.collection(FirestoreCollections.ITEMS).whereEqualTo("ownerId", ownerId)

    assertEquals(listOf("item1"), Tasks.await(ownItems.get()).documents.map { it.id })

    FirebaseEmulator.signInAs("outsider")
    assertDenied(ownItems.get())
  }

  @Test
  fun memberCanListSharedItemsByHousehold() {
    val household = createHouseholdAs("creator")
    Tasks.await(
        itemReference("item1")
            .set(
                mapOf(
                    "ownerId" to household.createdBy,
                    "householdId" to household.id,
                    "name" to "Apple")))
    joinHouseholdAs("member", household)
    val sharedItems =
        firestore.collection(FirestoreCollections.ITEMS).whereEqualTo("householdId", household.id)

    assertEquals(listOf("item1"), Tasks.await(sharedItems.get()).documents.map { it.id })

    FirebaseEmulator.signInAs("outsider")
    assertDenied(sharedItems.get())
  }

  // ======================= receipts =======================

  @Test
  fun userCanReadWriteAndDeleteOwnPrivateReceipt() {
    val userId = FirebaseEmulator.signInAs("owner")
    val receiptRef = receiptReference("receipt1")

    Tasks.await(receiptRef.set(mapOf("ownerId" to userId, "storeName" to "Migros")))
    assertEquals("Migros", Tasks.await(receiptRef.get()).getString("storeName"))
    Tasks.await(receiptRef.update("storeName", "Coop"))
    Tasks.await(receiptRef.delete())
  }

  @Test
  fun userCannotCreateReceiptForAnotherUser() {
    FirebaseEmulator.signInAs("owner")

    assertDenied(
        receiptReference("receipt1")
            .set(mapOf("ownerId" to "someone-else", "storeName" to "Migros")))
  }

  @Test
  fun nonMemberCannotReadOrWriteSharedReceipt() {
    val household = createHouseholdAs("creator")
    val receiptRef = receiptReference("receipt1")
    Tasks.await(
        receiptRef.set(
            mapOf(
                "ownerId" to household.createdBy,
                "householdId" to household.id,
                "storeName" to "Migros")))

    FirebaseEmulator.signInAs("outsider")
    assertDenied(receiptRef.get())
    assertDenied(receiptRef.update("storeName", "Coop"))
    assertDenied(receiptRef.delete())
  }

  @Test
  fun ownerCanShareAndUnshareOwnReceipt() {
    val household = createHouseholdAs("creator")
    val receiptRef = receiptReference("receipt1")
    Tasks.await(receiptRef.set(mapOf("ownerId" to household.createdBy, "storeName" to "Migros")))

    Tasks.await(receiptRef.update("householdId", household.id))
    Tasks.await(receiptRef.update("householdId", null))
  }

  @Test
  fun receiptWithMalformedHouseholdIsRejected() {
    val userId = FirebaseEmulator.signInAs("owner")

    assertDenied(
        receiptReference("receipt1")
            .set(mapOf("ownerId" to userId, "householdId" to 42, "storeName" to "Migros")))
  }

  // ======================= FirebaseItemRepository Coverage =======================

  @Test
  fun itemRepositoryCanCreateReadAndDeletePrivateItem() = runBlocking {
    val userId = FirebaseEmulator.signInAs("owner")

    // Create
    val itemId = itemRepository.addItem(Item(id = "", name = "Apple", ownerId = userId))
    assertNotNull(itemId)

    // Read
    val items = itemRepository.getPrivateItems(userId).first()
    assertEquals(1, items.size)
    assertEquals(itemId, items[0].id)
    assertEquals("Apple", items[0].name)

    // Delete
    assertTrue(itemRepository.deleteItem(itemId!!))

    // Verify deleted
    val itemsAfterDelete = itemRepository.getPrivateItems(userId).first()
    assertTrue(itemsAfterDelete.isEmpty())
  }

  @Test
  fun itemRepositoryCanCreateReadAndDeleteSharedItem() = runBlocking {
    val household = createHouseholdAs("creator")
    val memberId = joinHouseholdAs("member", household)

    // Create
    val itemId =
        itemRepository.addItem(
            Item(id = "", name = "Banana", ownerId = memberId, householdId = household.id))
    assertNotNull(itemId)

    // Read
    val items = itemRepository.getSharedItems(household.id).first()
    assertEquals(1, items.size)
    assertEquals(itemId, items[0].id)
    assertEquals("Banana", items[0].name)

    // Delete
    assertTrue(itemRepository.deleteItem(itemId!!))

    // Verify deleted
    val itemsAfterDelete = itemRepository.getSharedItems(household.id).first()
    assertTrue(itemsAfterDelete.isEmpty())
  }

  @Test
  fun itemRepositoryFailsToCreateItemForAnotherUser() = runBlocking {
    FirebaseEmulator.signInAs("owner")

    val itemId = itemRepository.addItem(Item(id = "", name = "Apple", ownerId = "someone-else"))

    // The repository catches the Permission Denied exception and returns null
    assertNull(itemId)
  }

  @Test
  fun itemRepositoryFailsToDeleteSomeoneElsesItem() = runBlocking {
    val ownerId = FirebaseEmulator.signInAs("owner")
    val itemId = itemRepository.addItem(Item(id = "", name = "Apple", ownerId = ownerId))
    assertNotNull(itemId)

    FirebaseEmulator.signInAs("outsider")

    // The repository catches the Permission Denied exception and returns false
    assertFalse(itemRepository.deleteItem(itemId!!))
  }

  @Test
  fun itemRepositoryFailsToAddSharedItemToForeignHousehold() = runBlocking {
    val otherHousehold = createHouseholdAs("other")
    val outsiderId = FirebaseEmulator.signInAs("outsider")

    val itemId =
        itemRepository.addItem(
            Item(id = "", name = "Apple", ownerId = outsiderId, householdId = otherHousehold.id))

    // The repository catches the Permission Denied exception and returns null
    assertNull(itemId)
  }

  // ======================= new helpers =======================

  /** Signs in as [alias] and joins [household] through the repository. Returns the user's uid. */
  private fun joinHouseholdAs(alias: String, household: Household): String {
    val userId = FirebaseEmulator.signInAs(alias)
    runBlocking { repository.joinHousehold(household.inviteCode, userId) }
    return userId
  }

  /** A household document that satisfies every rule, stored under id "household". */
  private fun wellFormedHousehold(userId: String): Map<String, Any> =
      mapOf(
          "id" to "household",
          "name" to "Home",
          "inviteCode" to "ABCDEF",
          "memberIds" to listOf(userId),
          "createdBy" to userId,
          "createdAt" to Date())

  /** The invite-code lookup document matching [wellFormedHousehold]. */
  private val validLookup: Map<String, Any> = mapOf("householdId" to "household")

  /** Same batch as the repository, but with caller-controlled documents. */
  private fun commitHousehold(data: Map<String, Any>, lookup: Map<String, Any>): Task<Void> {
    val userId = checkNotNull(FirebaseEmulator.auth.uid)
    return firestore
        .batch()
        .set(householdReference("household"), data)
        .set(inviteCodeReference("ABCDEF"), lookup)
        .set(userReference(userId), mapOf("householdId" to "household"), SetOptions.merge())
        .commit()
  }

  private fun itemReference(itemId: String): DocumentReference =
      firestore.collection(FirestoreCollections.ITEMS).document(itemId)

  private fun receiptReference(receiptId: String): DocumentReference =
      firestore.collection(FirestoreCollections.RECEIPTS).document(receiptId)

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
