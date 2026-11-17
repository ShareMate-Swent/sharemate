// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.UserProfileFields
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.model.image.*
import com.android.sharemate.model.item.*
import com.android.sharemate.model.user.ExpirationPreferencesProvider
import com.android.sharemate.model.user.FirebaseExpirationPreferencesRepository
import com.android.sharemate.model.user.FirebaseMemberNameRepository
import com.android.sharemate.model.user.FirebasePseudoRepository
import com.android.sharemate.resources.C
import com.android.sharemate.ui.theme.SampleAppTheme
import com.android.sharemate.utils.FirebaseEmulator
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OwnershipDisplayEmulatorTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val store = ViewModelStore()

  @Before
  fun setup() {
    FirebaseEmulator.clear()
  }

  @After
  fun cleanup() {
    compose.runOnUiThread { store.clear() }
    FirebaseEmulator.clear()
  }

  @Test
  fun realMemberNamesReachCardsAndRefreshWithoutChangingInventory() {
    val firestore = FirebaseEmulator.firestore
    val directory = FirebaseMemberNameRepository(firestore)
    val items = FirebaseItemRepository(firestore)
    val households = HouseholdRepositoryFirestore(firestore)
    val alice = FirebaseEmulator.signInAs("card-alice")
    val home = runBlocking { households.createHousehold("Home", alice) }
    runBlocking {
      firestore
          .collection(FirestoreCollections.USERS)
          .document(alice)
          .set(
              mapOf(
                  UserProfileFields.DISPLAY_NAME to "Alice",
                  UserProfileFields.EMAIL to "private@example.com"),
              SetOptions.merge())
          .await()
    }
    val bob = FirebaseEmulator.signInAs("card-bob")
    runBlocking {
      households.joinHousehold(home.inviteCode, bob)
      directory.publishOwnName(home.id, bob, "Bob")
    }
    val bobId = runBlocking {
      checkNotNull(
          items.addItem(
              Item(
                  name = "Oats",
                  ownerId = bob,
                  householdId = home.id,
                  sharing = ItemSharing.PERSONAL)))
    }
    val sharedId = runBlocking {
      checkNotNull(
          items.addItem(
              Item(
                  name = "Bread",
                  ownerId = bob,
                  householdId = home.id,
                  sharing = ItemSharing.SHARED)))
    }
    FirebaseEmulator.signInAs("card-alice")
    val aliceId = runBlocking {
      checkNotNull(
          items.addItem(
              Item(
                  name = "Milk",
                  ownerId = alice,
                  householdId = home.id,
                  sharing = ItemSharing.PERSONAL)))
    }
    lateinit var vm: FridgeViewModel
    compose.runOnUiThread {
      vm = ownerViewModel(alice, home.id, FridgeType.SHARED)
      store.put("ownership", vm)
    }
    compose.setContent { SampleAppTheme(dynamicColor = false) { FridgeScreen(vm) } }
    compose.waitUntil(10_000) {
      vm.uiState.value.items.size == 2 &&
          vm.uiState.value.ownerNames[alice] == "Alice" &&
          vm.uiState.value.ownerNames[bob] == "Bob"
    }
    compose.onNodeWithTag(C.Tag.fridge_sharing_badge_prefix + aliceId).assertTextEquals("Personal")
    compose
        .onNodeWithTag(C.Tag.fridge_owner_name_prefix + aliceId)
        .assertTextEquals("Owner: Alice (you)")
    compose.onNodeWithTag(C.Tag.fridge_item_prefix + bobId).assertDoesNotExist()
    compose
        .onNodeWithTag(C.Tag.fridge_item_prefix + sharedId)
        .assertContentDescriptionContains("Owner: Bob", substring = true)
    compose.onNodeWithTag(C.Tag.fridge_sharing_badge_prefix + sharedId).assertTextEquals("Shared")
    val before = vm.uiState.value.items
    runBlocking {
      firestore
          .collection(FirestoreCollections.USERS)
          .document(alice)
          .update(UserProfileFields.DISPLAY_NAME, "Alicia")
          .await()
      directory.publishOwnName(home.id, alice, null)
    }
    compose.waitUntil(10_000) { vm.uiState.value.ownerNames[alice] == "Alicia" }
    compose
        .onNodeWithTag(C.Tag.fridge_owner_name_prefix + aliceId)
        .assertTextEquals("Owner: Alicia (you)")
    assertEquals(before, vm.uiState.value.items)
    compose.onAllNodesWithText("private@example.com", substring = true).assertCountEquals(0)
  }

  @Test
  fun privateFridgeUsesAndUpdatesOnlyOwnPseudo() {
    val firestore = FirebaseEmulator.firestore
    val owner = FirebaseEmulator.signInAs("private-owner")
    val home = runBlocking {
      HouseholdRepositoryFirestore(firestore).createHousehold("Private", owner, FridgeType.PRIVATE)
    }
    val itemId = runBlocking {
      firestore
          .collection(FirestoreCollections.PROFILES)
          .document(owner)
          .set(mapOf(UserProfileFields.DISPLAY_NAME to "Private pseudo"))
          .await()
      checkNotNull(
          FirebaseItemRepository(firestore)
              .addItem(Item(name = "Milk", ownerId = owner, householdId = home.id)))
    }
    lateinit var vm: FridgeViewModel
    compose.runOnUiThread {
      vm = ownerViewModel(owner, home.id, FridgeType.PRIVATE)
      store.put("private-owner", vm)
    }
    compose.setContent { SampleAppTheme { FridgeScreen(vm) } }
    compose.waitUntil(10_000) {
      vm.uiState.value.ownerNames[owner] == "Private pseudo" && vm.uiState.value.items.size == 1
    }
    compose
        .onNodeWithTag(C.Tag.fridge_owner_name_prefix + itemId)
        .assertTextEquals("Owner: Private pseudo (you)")
    runBlocking {
      firestore
          .collection(FirestoreCollections.PROFILES)
          .document(owner)
          .update(UserProfileFields.DISPLAY_NAME, "Renamed pseudo")
          .await()
    }
    compose.waitUntil(10_000) { vm.uiState.value.ownerNames[owner] == "Renamed pseudo" }
    compose
        .onNodeWithTag(C.Tag.fridge_owner_name_prefix + itemId)
        .assertTextEquals("Owner: Renamed pseudo (you)")
    assertEquals(listOf(itemId), vm.uiState.value.items.map { it.id })
  }

  private fun ownerViewModel(
      userId: String,
      householdId: String,
      type: FridgeType
  ): FridgeViewModel {
    val firestore = FirebaseEmulator.firestore
    return FridgeViewModel(
        FirebaseItemRepository(firestore),
        userId,
        householdId,
        FoodImageSelector(
            object : FoodImageRepository {
              override suspend fun searchByTitle(title: String) = FoodImageSearchResult.NotFound
            }),
        testBarcodeScanner(),
        testProductCatalogRepository(),
        expirationPreferences =
            ExpirationPreferencesProvider(FirebaseExpirationPreferencesRepository(firestore)),
        pseudoRepository = FirebasePseudoRepository(firestore),
        fridgeType = type,
        memberNameRepository = FirebaseMemberNameRepository(firestore),
        currentUserDisplayName = null)
  }
}
