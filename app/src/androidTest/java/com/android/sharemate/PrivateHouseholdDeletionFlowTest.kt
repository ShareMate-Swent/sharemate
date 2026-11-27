// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.household.FirebasePrivateHouseholdDeletionRepository
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.ui.household.HouseholdChoiceTestTags
import com.android.sharemate.ui.navigation.NavigationTestTags
import com.android.sharemate.ui.settings.SettingsTestTags
import com.android.sharemate.utils.FirebaseEmulator
import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/**
 * Leaving a private household from Settings deletes it, with real Firestore, Storage, Auth and
 * Functions emulators: the household, its item, the item's photo, the linked receipt and the link
 * on the profile are gone, and the app is back on the household choice.
 *
 * The data is checked with the emulators' administrative endpoints, because after the deletion the
 * rules no longer let the user read any of it.
 */
@RunWith(AndroidJUnit4::class)
class PrivateHouseholdDeletionFlowTest {
  private lateinit var userId: String
  private lateinit var householdId: String

  @get:Rule(order = 0)
  val firebase =
      object : ExternalResource() {
        override fun before() {
          FirebaseEmulator.clear()
          userId = FirebaseEmulator.signInAs(ALIAS)
          FirebaseFunctions.getInstance(FirebasePrivateHouseholdDeletionRepository.REGION)
              .useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.FUNCTIONS_PORT)
          householdId = seedPrivateHouseholdWithContent()
        }

        override fun after() {
          FirebaseEmulator.deleteStorageObjects(FirebaseEmulator.functionsBucket, PHOTOS_PREFIX)
          FirebaseEmulator.clear()
        }
      }
  @get:Rule(order = 1) val compose = createAndroidComposeRule<MainUserFlowTestActivity>()

  @Test
  fun tappingDeleteRemovesTheHouseholdAndItsContentAndReturnsToTheHouseholdChoice() {
    waitFor(NavigationTestTags.FRIDGE_TAB)
    assertTrue(isStored("households/$householdId"))
    assertTrue(isStored("${FirestoreCollections.ITEMS}/$ITEM_ID"))
    assertTrue(isStored("${FirestoreCollections.RECEIPTS}/$RECEIPT_ID"))
    assertEquals(listOf(photoName()), photoNames())

    compose.onNodeWithTag(NavigationTestTags.PROFILE_ENTRY).performClick()
    compose.onNodeWithTag(SettingsTestTags.LEAVE_HOUSEHOLD).performScrollTo().performClick()
    compose.onNodeWithTag(SettingsTestTags.LEAVE_DIALOG).assertIsDisplayed()
    compose.onNodeWithTag(SettingsTestTags.LEAVE_CONFIRM).performClick()

    waitFor(HouseholdChoiceTestTags.CREATE, DELETION_TIMEOUT_MILLIS)
    compose.onNodeWithTag(HouseholdChoiceTestTags.CREATE).assertIsDisplayed()
    assertFalse(isStored("households/$householdId"))
    assertFalse(isStored("${FirestoreCollections.ITEMS}/$ITEM_ID"))
    assertFalse(isStored("${FirestoreCollections.RECEIPTS}/$RECEIPT_ID"))
    assertEquals(emptyList<String>(), photoNames())
    val profile = FirebaseEmulator.documentFields("${FirestoreCollections.USERS}/$userId")
    assertFalse(profile?.has(HOUSEHOLD_ID_FIELD) ?: false)
  }

  /** Creates the private household, one item with a photo and one receipt linked to it. */
  private fun seedPrivateHouseholdWithContent(): String {
    val firestore = FirebaseEmulator.firestore
    val household = runBlocking {
      HouseholdRepositoryFirestore(firestore)
          .createHousehold("My private fridge", userId, FridgeType.PRIVATE)
    }
    val item =
        mapOf(
            "ownerId" to userId,
            HOUSEHOLD_ID_FIELD to household.id,
            "name" to "Apple",
            "quantity" to 1,
            "status" to "ACTIVE",
            "sharing" to "PERSONAL")
    val receipt =
        mapOf(
            "ownerId" to userId,
            HOUSEHOLD_ID_FIELD to household.id,
            "storeName" to "Shop",
            "totalAmountCents" to 100L)
    Tasks.await(firestore.collection(FirestoreCollections.ITEMS).document(ITEM_ID).set(item))
    Tasks.await(
        firestore.collection(FirestoreCollections.RECEIPTS).document(RECEIPT_ID).set(receipt))
    FirebaseEmulator.seedStorageObject(
        FirebaseEmulator.functionsBucket, photoName(), PHOTO_BYTES, PHOTO_CONTENT_TYPE)
    return household.id
  }

  private fun isStored(path: String) = FirebaseEmulator.documentFields(path) != null

  private fun photoName() = "$PHOTOS_PREFIX$ITEM_ID/$PHOTO_ID"

  private fun photoNames() =
      FirebaseEmulator.storageObjectNames(
          FirebaseEmulator.functionsBucket, "$PHOTOS_PREFIX$ITEM_ID/")

  private fun waitFor(tag: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS) {
    compose.waitUntil(timeoutMillis) {
      compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private companion object {
    const val ALIAS = "deletion-flow"
    const val HOUSEHOLD_ID_FIELD = "householdId"
    const val ITEM_ID = "deletion-flow-item"
    const val RECEIPT_ID = "deletion-flow-receipt"
    const val PHOTO_ID = "7f1c2d3e-4a5b-4c6d-8e9f-0a1b2c3d4e5f"
    const val PHOTOS_PREFIX = "itemPhotos/"
    const val PHOTO_CONTENT_TYPE = "image/png"
    val PHOTO_BYTES = "photo".toByteArray()
    const val DEFAULT_TIMEOUT_MILLIS = 15_000L

    /** The first call can wait for the Functions emulator to load the function. */
    const val DELETION_TIMEOUT_MILLIS = 90_000L
  }
}
