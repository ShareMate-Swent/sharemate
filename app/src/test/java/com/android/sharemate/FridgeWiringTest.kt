// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStoreOwner
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.item.Item
import com.android.sharemate.resources.C
import com.android.sharemate.ui.household.HouseholdGateViewModel
import com.android.sharemate.ui.navigation.NavigationTestTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FridgeWiringTest {
  @get:Rule(order = 0)
  val firebase = FirebaseAuthTestRule(startSignedIn = true, pendingHouseholdLookup = true)
  @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun inventoryIsNotObservedUntilHouseholdIsResolved() {
    compose.onNodeWithTag("household_loading").assertExists()
    compose.onNodeWithTag(C.Tag.fridge_type).assertDoesNotExist()
    assertEquals(emptyList<String>(), firebase.observedHouseholds)
    resolve()
    compose.onNodeWithTag(C.Tag.fridge_type).assertTextEquals("Fridge type: Shared")
    compose.onNodeWithTag(C.Tag.fridge_add_item).assertIsEnabled()
    assertEquals(listOf("student-home"), firebase.observedHouseholds)
  }

  @Test
  fun noHouseholdShowsOnboardingWithoutObservingInventory() {
    compose.runOnIdle { firebase.resolveInitialHousehold(null) }
    compose.onNodeWithTag("household_sign_out").assertExists()
    compose.onNodeWithTag(C.Tag.fridge_screen_container).assertDoesNotExist()
    assertEquals(emptyList<String>(), firebase.observedHouseholds)
  }

  @Test
  fun inventoryUpdatesAndTabNavigationDoNotDuplicateObservation() {
    resolve(FridgeType.PRIVATE)
    compose.onNodeWithTag(C.Tag.fridge_type).assertTextEquals("Fridge type: Private")
    emit("student-home", "Milk")
    compose.onNodeWithText("Milk").assertExists()
    emit("student-home", "Bread")
    compose.onNodeWithText("Bread").assertExists()
    compose.onNodeWithText("Milk").assertDoesNotExist()
    compose.onNodeWithTag(NavigationTestTags.SETTINGS_TAB).performClick()
    compose.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).performClick()
    compose.onNodeWithTag(C.Tag.fridge_type).assertTextEquals("Fridge type: Private")
    compose.onNodeWithText("Bread").assertExists()
    assertEquals(listOf("student-home"), firebase.observedHouseholds)
    assertEquals(emptyList<String>(), firebase.removedListeners)
  }

  @Test
  fun signOutRemovesInventoryListenerEvenWhenFridgeEntryIsSaved() {
    resolve()
    compose.onNodeWithTag(NavigationTestTags.SETTINGS_TAB).performClick()
    compose.onNodeWithText("Sign out").performClick()
    compose.onNodeWithTag("auth_welcome").assertExists()
    compose.onNodeWithTag(C.Tag.fridge_type).assertDoesNotExist()
    compose.waitUntil { firebase.removedListeners == listOf("student-home") }
    assertEquals(listOf("student-home"), firebase.observedHouseholds)
  }

  @Test
  fun accountChangeClearsPreviousInventoryAndUsesNewHousehold() {
    resolve(FridgeType.PRIVATE)
    compose.onNodeWithTag(C.Tag.fridge_type).assertTextEquals("Fridge type: Private")
    emit("student-home", "Old inventory")
    compose.onNodeWithText("Old inventory").assertExists()
    compose.runOnIdle {
      firebase.setHousehold("other-user", "other-home")
      firebase.setSession("other-user")
    }
    compose.waitUntil { firebase.observedHouseholds == listOf("student-home", "other-home") }
    compose.waitUntil { firebase.removedListeners == listOf("student-home") }
    compose.onNodeWithText("Old inventory").assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.fridge_type).assertTextEquals("Fridge type: Shared")
    emit("other-home", "New inventory")
    compose.onNodeWithText("New inventory").assertExists()
  }

  @Test
  fun householdRefreshClearsSavedInventoryBeforeObservingReplacement() {
    resolve()
    emit("student-home", "Old inventory")
    compose.onNodeWithText("Old inventory").assertExists()
    compose.onNodeWithTag(NavigationTestTags.SETTINGS_TAB).performClick()
    compose.runOnIdle {
      firebase.setHousehold("trusted-student-id", "replacement-home")
      val activityStore = compose.activity.viewModelStore
      val householdStore =
          activityStore
              .keys()
              .mapNotNull { activityStore[it] }
              .filterIsInstance<ViewModelStoreOwner>()
              .single()
              .viewModelStore
      householdStore
          .keys()
          .mapNotNull { householdStore[it] }
          .filterIsInstance<HouseholdGateViewModel>()
          .single()
          .refresh()
    }
    compose.waitUntil { firebase.observedHouseholds.contains("replacement-home") }
    compose.waitUntil { firebase.removedListeners.contains("student-home") }
    compose.onNodeWithText("Old inventory").assertDoesNotExist()
    emit("replacement-home", "Replacement inventory")
    compose.onNodeWithText("Replacement inventory").assertExists()
  }

  @Test
  fun activityRecreationRetainsTheInventoryListener() {
    resolve(FridgeType.PRIVATE)
    emit("student-home", "Milk")
    compose.activityRule.scenario.recreate()
    compose.onNodeWithText("Milk").assertExists()
    compose.onNodeWithTag(C.Tag.fridge_type).assertTextEquals("Fridge type: Private")
    assertEquals(listOf("student-home"), firebase.observedHouseholds)
    assertEquals(emptyList<String>(), firebase.removedListeners)
  }

  private fun resolve(type: FridgeType = FridgeType.SHARED) {
    compose.runOnIdle { firebase.resolveInitialHousehold(type = type) }
    compose.onNodeWithTag(C.Tag.fridge_add_item).assertIsEnabled()
  }

  private fun emit(householdId: String, name: String) {
    compose.runOnIdle {
      firebase.emitItems(
          householdId,
          listOf(
              Item(
                  id = "item-$householdId",
                  name = name,
                  ownerId = "trusted-student-id",
                  householdId = householdId)))
    }
  }
}
