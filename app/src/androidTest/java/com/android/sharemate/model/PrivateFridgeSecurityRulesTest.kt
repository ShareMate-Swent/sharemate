// Co-authored-by: OpenAI Codex
package com.android.sharemate.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.utils.FirebaseEmulator
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import java.util.Date
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises deployed rules through authenticated Firestore emulator clients. */
@RunWith(AndroidJUnit4::class)
class PrivateFridgeSecurityRulesTest {
  private val firestore = FirebaseEmulator.firestore
  private val repository = HouseholdRepositoryFirestore(firestore)
  private val households
    get() = firestore.collection(FirestoreCollections.HOUSEHOLDS)

  private val users
    get() = firestore.collection(FirestoreCollections.USERS)

  private val codes
    get() = firestore.collection(FirestoreCollections.INVITE_CODES)

  @Before fun setUp() = FirebaseEmulator.clear()

  @After fun tearDown() = FirebaseEmulator.clear()

  @Test
  fun ownerCanReadQueryAndLeavePrivateFridgeWithoutAnInvite() = runBlocking {
    val owner = FirebaseEmulator.signInAs("owner")
    val fridge = createFridge("private", owner, FridgeType.PRIVATE)

    assertEquals(fridge, repository.getHousehold(fridge.id))
    assertEquals(fridge, repository.getHouseholdForUser(owner))
    assertEquals("", fridge.inviteCode)
    repository.leaveHousehold(owner)

    assertNull(repository.getHouseholdForUser(owner))
    assertEquals(emptyList<String>(), repository.getHousehold(fridge.id)?.memberIds)
    assertFalse(Tasks.await(users.document(owner).get()).contains("householdId"))
  }

  @Test
  fun outsiderCannotReadPrivateFridgeEvenWithForgedProfileOrKnownCode() = runBlocking {
    val owner = FirebaseEmulator.signInAs("owner")
    val fridge = seedLegacyPrivateFridge(owner)
    val outsider = FirebaseEmulator.signInAs("outsider")
    Tasks.await(users.document(outsider).set(mapOf("householdId" to fridge.id)))

    assertDenied(households.document(fridge.id).get())
    assertDenied(codes.document(fridge.inviteCode).get())
    assertDenied(households.whereArrayContains("memberIds", owner).get())
    assertDenied(households.whereEqualTo("createdBy", owner).get())
    assertDenied(households.get())
    assertNull(repository.getHouseholdForUser(outsider))
    assertEquals(
        emptyList<String>(),
        Tasks.await(households.whereArrayContains("memberIds", outsider).get()).documents.map {
          it.id
        })

    FirebaseEmulator.signOut()
    assertDenied(households.document(fridge.id).get())
    assertDenied(codes.document(fridge.inviteCode).get())
    assertDenied(households.whereArrayContains("memberIds", owner).get())
  }

  @Test
  fun joiningPrivateFridgeIsDeniedAtomicallyAndOwnerCannotAddOtherMembers() {
    val owner = FirebaseEmulator.signInAs("owner")
    val fridge = createFridge("private", owner, FridgeType.PRIVATE)
    val outsider = FirebaseEmulator.signInAs("outsider")
    val outsiderProfile = users.document(outsider)
    Tasks.await(outsiderProfile.set(mapOf("displayName" to "Outsider")))

    assertDenied(
        firestore
            .batch()
            .update(households.document(fridge.id), "memberIds", FieldValue.arrayUnion(outsider))
            .set(outsiderProfile, mapOf("householdId" to fridge.id), SetOptions.merge())
            .commit())
    assertFalse(Tasks.await(outsiderProfile.get()).contains("householdId"))
    assertEquals("Outsider", Tasks.await(outsiderProfile.get()).getString("displayName"))

    FirebaseEmulator.signInAs("owner")
    assertDenied(households.document(fridge.id).update("memberIds", listOf(owner, outsider)))
    assertDenied(households.document(fridge.id).update("createdBy", outsider))
    assertDenied(households.document(fridge.id).update("type", "SHARED"))
    assertEquals(listOf(owner), Tasks.await(households.document(fridge.id).get()).get("memberIds"))

    val invalid =
        Household(
            "invalid", "Private", "", listOf(owner, outsider), owner, Date(), FridgeType.PRIVATE)
    assertDenied(writeFridge(invalid))
  }

  @Test
  fun privateItemsAndReceiptsAreUsableOnlyByOwnerIncludingAfterLeaving() = runBlocking {
    val owner = FirebaseEmulator.signInAs("owner")
    val fridge = createFridge("private", owner, FridgeType.PRIVATE)
    val item = firestore.collection(FirestoreCollections.ITEMS).document("food")
    val receipt = firestore.collection(FirestoreCollections.RECEIPTS).document("receipt")
    val itemData = mapOf("ownerId" to owner, "householdId" to fridge.id, "name" to "Apple")
    val receiptData =
        mapOf(
            "ownerId" to owner,
            "householdId" to fridge.id,
            "storeName" to "Shop",
            "totalAmountCents" to 100L)
    Tasks.await(item.set(itemData))
    Tasks.await(receipt.set(receiptData))
    repository.leaveHousehold(owner)
    assertEquals("Apple", Tasks.await(item.get()).getString("name"))
    assertEquals("Shop", Tasks.await(receipt.get()).getString("storeName"))
    Tasks.await(item.update("name", "Pear"))
    Tasks.await(receipt.update("storeName", "Other shop"))
    assertEquals(
        listOf("food"),
        Tasks.await(item.parent.whereEqualTo("householdId", fridge.id).get()).documents.map {
          it.id
        })
    assertEquals(
        listOf("receipt"),
        Tasks.await(receipt.parent.whereEqualTo("householdId", fridge.id).get()).documents.map {
          it.id
        })

    val outsider = FirebaseEmulator.signInAs("outsider")
    Tasks.await(users.document(outsider).set(mapOf("householdId" to fridge.id)))
    for (reference in listOf(item, receipt)) {
      assertDenied(reference.get())
      assertDenied(reference.delete())
      assertDenied(reference.update("ownerId", outsider))
      assertDenied(reference.parent.whereEqualTo("householdId", fridge.id).get())
    }
    assertDenied(item.update("name", "Stolen"))
    assertDenied(receipt.update("storeName", "Stolen"))
    assertDenied(item.parent.document("outsider").set(itemData + ("ownerId" to outsider)))
    assertDenied(receipt.parent.document("outsider").set(receiptData + ("ownerId" to outsider)))
    assertDenied(item.parent.document("forged").set(itemData))
    assertDenied(receipt.parent.document("forged").set(receiptData))

    FirebaseEmulator.signOut()
    assertDenied(item.get())
    assertDenied(receipt.get())
    assertDenied(item.update("name", "Signed out"))
    assertDenied(receipt.delete())
    FirebaseEmulator.signInAs("owner")
    Tasks.await(item.delete())
    Tasks.await(receipt.delete())
    Unit
  }

  @Test
  fun membershipQueryReturnsOwnPrivateAndSharedAndLegacyFridges() = runBlocking {
    val owner = FirebaseEmulator.signInAs("owner")
    createFridge("private", owner, FridgeType.PRIVATE)
    val shared = createFridge("shared", owner, FridgeType.SHARED)
    val legacy = createFridge("legacy", owner, null)

    assertEquals(
        setOf("private", "shared", "legacy"),
        Tasks.await(households.whereArrayContains("memberIds", owner).get())
            .documents
            .map { it.id }
            .toSet())
    val member = FirebaseEmulator.signInAs("member")
    repository.joinHousehold(shared.inviteCode, member)
    repository.joinHousehold(legacy.inviteCode, member)

    assertEquals(
        setOf("shared", "legacy"),
        Tasks.await(households.whereArrayContains("memberIds", member).get())
            .documents
            .map { it.id }
            .toSet())
    assertEquals(FridgeType.SHARED, repository.getHousehold(legacy.id)?.type)
    assertDenied(households.document("private").get())
  }

  private fun createFridge(id: String, owner: String, type: FridgeType?): Household {
    val fridge =
        Household(
            id,
            "Home",
            if (type == FridgeType.PRIVATE) "" else id.uppercase(),
            listOf(owner),
            owner,
            Date(),
            type ?: FridgeType.SHARED)
    val data: Any =
        if (type != null) fridge
        else
            mapOf(
                "id" to fridge.id,
                "name" to fridge.name,
                "inviteCode" to fridge.inviteCode,
                "memberIds" to fridge.memberIds,
                "createdBy" to fridge.createdBy,
                "createdAt" to fridge.createdAt)
    Tasks.await(writeFridge(fridge, data))
    return fridge
  }

  @Test
  fun privateCreationRejectsNonemptyCodesAndNewInviteMappings() {
    val owner = FirebaseEmulator.signInAs("owner")
    val invalid =
        Household("invalid", "Mine", "PRIVATE", listOf(owner), owner, Date(), FridgeType.PRIVATE)
    assertDenied(households.document(invalid.id).set(invalid))
    assertDenied(writeFridge(invalid))

    val fridge = createFridge("private", owner, FridgeType.PRIVATE)
    assertDenied(codes.document("NEWCODE").set(mapOf("householdId" to fridge.id)))
    assertEquals("", Tasks.await(households.document(fridge.id).get()).getString("inviteCode"))
    assertFalse(Tasks.await(codes.document("PRIVATE").get()).exists())
    assertFalse(Tasks.await(codes.document("NEWCODE").get()).exists())
  }

  @Test
  fun sharedCreationRequiresAnInviteMapping() {
    val owner = FirebaseEmulator.signInAs("owner")
    val shared =
        Household("shared", "Home", "SHARED", listOf(owner), owner, Date(), FridgeType.SHARED)
    assertDenied(households.document(shared.id).set(shared))
    assertDenied(households.document(shared.id).set(shared.copy(inviteCode = "")))
    Tasks.await(writeFridge(shared))
    assertEquals(
        shared.id, Tasks.await(codes.document(shared.inviteCode).get()).getString("householdId"))
  }

  @Test
  fun legacyPrivateCodeRemainsReadableByOwnerAfterLeaving() = runBlocking {
    val owner = FirebaseEmulator.signInAs("owner")
    val fridge = seedLegacyPrivateFridge(owner)
    assertEquals(
        fridge.id, Tasks.await(codes.document(fridge.inviteCode).get()).getString("householdId"))
    assertEquals(fridge, repository.getHousehold(fridge.id))
    repository.leaveHousehold(owner)
    assertEquals(
        fridge.id, Tasks.await(codes.document(fridge.inviteCode).get()).getString("householdId"))
    assertEquals(fridge, repository.joinHousehold(fridge.inviteCode, owner))
  }

  private fun seedLegacyPrivateFridge(owner: String): Household {
    createFridge("private", owner, FridgeType.PRIVATE)
    FirebaseEmulator.seedLegacyDocument(
        "households", "private", """{"inviteCode":{"stringValue":"LEGACY"}}""")
    FirebaseEmulator.seedLegacyDocument(
        "inviteCodes", "LEGACY", """{"householdId":{"stringValue":"private"}}""")
    return Tasks.await(households.document("private").get()).toObject(Household::class.java)!!
  }

  private fun writeFridge(fridge: Household, data: Any = fridge): Task<Void> {
    val batch = firestore.batch().set(households.document(fridge.id), data)
    if (fridge.inviteCode.isNotEmpty()) {
      batch.set(codes.document(fridge.inviteCode), mapOf("householdId" to fridge.id))
    }
    return batch
        .set(
            users.document(fridge.createdBy), mapOf("householdId" to fridge.id), SetOptions.merge())
        .commit()
  }

  private fun assertDenied(task: Task<*>) {
    val failure = assertThrows(ExecutionException::class.java) { Tasks.await(task) }
    assertEquals(
        FirebaseFirestoreException.Code.PERMISSION_DENIED,
        (failure.cause as? FirebaseFirestoreException)?.code)
  }
}
