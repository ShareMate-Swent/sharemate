// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.barcode.BarcodeScanResult
import com.android.sharemate.model.barcode.BarcodeScanner
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.model.image.FoodImageRepository
import com.android.sharemate.model.image.FoodImageSearchResult
import com.android.sharemate.model.image.FoodImageSelector
import com.android.sharemate.model.item.FirebaseItemRepository
import com.android.sharemate.model.item.ItemImage
import com.android.sharemate.model.item.ItemSharing
import com.android.sharemate.model.user.ExpirationPreferences
import com.android.sharemate.model.user.ExpirationPreferencesProvider
import com.android.sharemate.model.user.ExpirationPreferencesRepository
import com.android.sharemate.resources.C
import com.android.sharemate.ui.theme.SampleAppTheme
import com.android.sharemate.utils.FirebaseEmulator
import com.google.firebase.firestore.Source
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ItemSharingDialogTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val store = ViewModelStore()

  @Before
  fun resetEmulators() {
    FirebaseEmulator.clear()
  }

  @After
  fun tearDown() {
    compose.runOnUiThread { store.clear() }
    FirebaseEmulator.clear()
  }

  @Test
  fun nonOwnerSelectionIsVisibleReadOnlyAndSavingDisablesChoices() {
    val state =
        mutableStateOf(
            FridgeUiState(
                fridgeType = FridgeType.SHARED,
                isAddItemDialogOpen = true,
                editingItemId = "milk",
                itemName = "Milk",
                itemSharing = ItemSharing.PERSONAL,
                showSharingSelector = true))
    var changes = 0
    compose.setContent {
      SampleAppTheme(dynamicColor = false) {
        AddItemDialog(
            state.value,
            {},
            {},
            {},
            {},
            {},
            onSharingChange = {
              changes++
              state.value = state.value.copy(itemSharing = it)
            })
      }
    }
    compose
        .onNodeWithTag(C.Tag.fridge_sharing_personal)
        .performScrollTo()
        .assertIsSelected()
        .assertIsNotEnabled()
    compose
        .onNodeWithTag(C.Tag.fridge_sharing_shared)
        .performScrollTo()
        .assertIsNotSelected()
        .assertIsNotEnabled()
        .performTouchInput { click() }
    assertEquals(0, changes)
    compose.onNodeWithTag(C.Tag.fridge_name_input).performScrollTo().assertIsEnabled()
    compose.onNodeWithTag(C.Tag.fridge_save_item).assertIsEnabled()
    compose.onNodeWithTag("barcode_photo_open").assertDoesNotExist()
    compose.runOnIdle { state.value = state.value.copy(canChangeSharing = true, isSaving = true) }
    compose.onNodeWithTag(C.Tag.fridge_sharing_personal).performScrollTo().assertIsNotEnabled()
    compose.onNodeWithTag(C.Tag.fridge_sharing_shared).assertIsNotEnabled()
    compose.onNodeWithTag(C.Tag.fridge_save_item).assertIsNotEnabled()
    compose.onNodeWithTag(C.Tag.fridge_cancel_item).assertIsNotEnabled()
  }

  @Test
  fun hiddenSelectorPreservesOrdinaryFormAndPhotoEntry() {
    var saves = 0
    compose.setContent {
      SampleAppTheme(dynamicColor = false) {
        AddItemDialog(
            FridgeUiState(
                fridgeType = FridgeType.PRIVATE, isAddItemDialogOpen = true, itemName = "Milk"),
            {},
            {},
            {},
            { saves++ },
            {})
      }
    }
    compose.onNodeWithTag(C.Tag.fridge_sharing_selector).assertDoesNotExist()
    compose.onNodeWithTag("barcode_photo_open").assertIsDisplayed().assertIsEnabled()
    compose.onNodeWithTag(C.Tag.fridge_name_input).assertIsEnabled()
    compose.onNodeWithTag(C.Tag.fridge_save_item).assertIsEnabled().performClick()
    assertEquals(1, saves)
  }

  @Test
  fun runtimeFormCreatesBothChoicesEditsBothWaysAndDiscardsCancelledDraft() {
    val repository = FirebaseItemRepository(FirebaseEmulator.firestore)
    val owner = FirebaseEmulator.signInAs("sharing-ui-owner")
    val home = runBlocking {
      HouseholdRepositoryFirestore(FirebaseEmulator.firestore).createHousehold("Home", owner)
    }
    val images =
        object : FoodImageRepository {
          override suspend fun searchByTitle(title: String) = FoodImageSearchResult.NotFound
        }
    lateinit var vm: FridgeViewModel
    compose.runOnUiThread {
      vm =
          FridgeViewModel(
              repository,
              owner,
              home.id,
              FoodImageSelector(images),
              BarcodeScanner { BarcodeScanResult.NotFound },
              testProductCatalogRepository(),
              expirationPreferences =
                  ExpirationPreferencesProvider(
                      object : ExpirationPreferencesRepository {
                        override fun observe(userId: String) = flowOf(ExpirationPreferences())

                        override suspend fun get(userId: String) = ExpirationPreferences()

                        override suspend fun save(
                            userId: String,
                            preferences: ExpirationPreferences
                        ) {}
                      }),
              fridgeType = FridgeType.SHARED)
      store.put("sharing-dialog", vm)
    }
    compose.setContent { SampleAppTheme(dynamicColor = false) { FridgeScreen(vm) } }
    compose.onNodeWithTag(C.Tag.fridge_add_item).performClick()
    compose.onNodeWithTag(C.Tag.fridge_name_input).performTextInput("Milk")
    compose.onNodeWithTag(C.Tag.fridge_share_switch).performScrollTo().assertIsOff()
    assertTrue(
        runBlocking {
          FirebaseEmulator.firestore
              .collection("items")
              .whereEqualTo("ownerId", owner)
              .get(Source.SERVER)
              .await()
              .isEmpty
        })
    compose.onNodeWithTag(C.Tag.fridge_save_item).performClick()
    compose.waitUntil(5_000) {
      !vm.uiState.value.isAddItemDialogOpen && vm.uiState.value.items.size == 1
    }
    val saved = vm.uiState.value.items.single()
    assertEquals(ItemSharing.PERSONAL, saved.sharing)
    assertEquals(owner, saved.ownerId)
    assertEquals(home.id, saved.householdId)
    val withImage = saved.copy(image = ItemImage(reference = "content://photos/milk"))
    runBlocking {
      FirebaseEmulator.firestore.collection("items").document(saved.id).set(withImage).await()
    }
    compose.waitUntil(5_000) { vm.uiState.value.items.singleOrNull()?.image == withImage.image }
    compose.waitForIdle()
    compose.runOnIdle { vm.openEditItemDialog(saved.id) }
    compose.onNodeWithTag("barcode_photo_open").assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.fridge_sharing_personal).performScrollTo().assertIsSelected()
    compose.onNodeWithTag(C.Tag.fridge_sharing_shared).performScrollTo().performClick()
    assertEquals(
        ItemSharing.PERSONAL,
        runBlocking {
          FirebaseEmulator.firestore
              .collection("items")
              .document(saved.id)
              .get(Source.SERVER)
              .await()
              .getString("sharing")
              ?.let(ItemSharing::valueOf)
        })
    compose.onNodeWithTag(C.Tag.fridge_save_item).performClick()
    compose.waitUntil(5_000) {
      !vm.uiState.value.isAddItemDialogOpen &&
          vm.uiState.value.items.singleOrNull()?.sharing == ItemSharing.SHARED
    }
    assertEquals(
        "SHARED",
        runBlocking {
          FirebaseEmulator.firestore
              .collection("items")
              .document(saved.id)
              .get(Source.SERVER)
              .await()
              .getString("sharing")
        })
    assertEquals(withImage.copy(sharing = ItemSharing.SHARED), vm.uiState.value.items.single())
    compose.runOnIdle { vm.openEditItemDialog(saved.id) }
    compose.onNodeWithTag(C.Tag.fridge_sharing_personal).performScrollTo().performClick()
    compose.onNodeWithTag(C.Tag.fridge_cancel_item).performClick()
    assertEquals(
        "SHARED",
        runBlocking {
          FirebaseEmulator.firestore
              .collection("items")
              .document(saved.id)
              .get(Source.SERVER)
              .await()
              .getString("sharing")
        })
    compose.runOnIdle { vm.openEditItemDialog(saved.id) }
    compose.onNodeWithTag(C.Tag.fridge_sharing_shared).performScrollTo().assertIsSelected()
    compose.onNodeWithTag(C.Tag.fridge_sharing_personal).performScrollTo().performClick()
    compose.onNodeWithTag(C.Tag.fridge_save_item).performClick()
    compose.waitUntil(5_000) {
      !vm.uiState.value.isAddItemDialogOpen &&
          vm.uiState.value.items.singleOrNull()?.sharing == ItemSharing.PERSONAL
    }
    assertEquals(withImage, vm.uiState.value.items.single())
    assertEquals(
        "PERSONAL",
        runBlocking {
          FirebaseEmulator.firestore
              .collection("items")
              .document(saved.id)
              .get(Source.SERVER)
              .await()
              .getString("sharing")
        })
    compose.onNodeWithTag(C.Tag.fridge_add_item).performClick()
    compose.onNodeWithTag(C.Tag.fridge_name_input).performTextInput("Bread")
    compose.onNodeWithTag(C.Tag.fridge_share_switch).performScrollTo().assertIsOff().performClick()
    compose.onNodeWithTag(C.Tag.fridge_save_item).performClick()
    compose.waitUntil(5_000) {
      !vm.uiState.value.isAddItemDialogOpen && vm.uiState.value.items.size == 2
    }
    assertEquals(ItemSharing.SHARED, vm.uiState.value.items.single { it.name == "Bread" }.sharing)
  }
}
