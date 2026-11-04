// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.item.*
import com.android.sharemate.resources.C
import com.android.sharemate.ui.theme.SampleAppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp")
class OwnershipCardTest {
  @get:Rule val compose = createComposeRule()
  private val mine = Item("mine", "Milk", "alice", "home", sharing = ItemSharing.PERSONAL)
  private val other = mine.copy(id = "other", name = "Oats", ownerId = "bob")

  private fun badge(id: String) = compose.onNodeWithTag(C.Tag.fridge_sharing_badge_prefix + id)

  private fun owner(id: String) = compose.onNodeWithTag(C.Tag.fridge_owner_name_prefix + id)

  @Test
  fun threeColumnCardsShowExplicitSharingAndEachOwnersName() {
    compose.setContent {
      SampleAppTheme(dynamicColor = false) {
        FridgeScreen(
            uiState =
                FridgeUiState(
                    items =
                        listOf(
                            mine, other, other.copy(id = "shared", sharing = ItemSharing.SHARED)),
                    currentUserId = "alice",
                    ownerNames = mapOf("alice" to "Alice", "bob" to "Robert Long Family Name")))
      }
    }
    badge("mine").assertTextEquals("Personal")
    badge("other").assertTextEquals("Personal")
    badge("shared").assertTextEquals("Shared")
    owner("mine").assertTextEquals("Owner: Alice (you)")
    owner("other").assertTextEquals("Owner: Robert Long Family Name")
    compose
        .onNodeWithTag(C.Tag.fridge_item_prefix + "other")
        .assertContentDescriptionContains(
            "Oats, Personal, Owner: Robert Long Family Name", substring = true)
  }

  @Test
  fun namesRecomposeAfterInventoryLoadsWithoutChangingCardIdentity() {
    val state = mutableStateOf(FridgeUiState(isLoading = true, currentUserId = "alice"))
    compose.setContent { SampleAppTheme { FridgeScreen(uiState = state.value) } }
    compose.runOnIdle { state.value = state.value.copy(isLoading = false, items = listOf(other)) }
    owner("other").assertTextEquals("Owner: Unknown owner")
    compose.runOnIdle { state.value = state.value.copy(ownerNames = mapOf("bob" to "Bob")) }
    owner("other").assertTextEquals("Owner: Bob")
    compose.runOnIdle { state.value = state.value.copy(ownerNames = emptyMap()) }
    owner("other").assertTextEquals("Owner: Unknown owner")
    compose.onAllNodesWithTag(C.Tag.fridge_item_prefix + "other").assertCountEquals(1)
  }

  @Test
  fun privateAndLegacyItemsUseSharingRatherThanHouseholdOrCurrentUser() {
    compose.setContent {
      SampleAppTheme {
        FridgeScreen(
            fridgeType = FridgeType.PRIVATE,
            uiState =
                FridgeUiState(
                    currentUserId = "alice",
                    ownerNames = mapOf("bob" to "email@example.com"),
                    items =
                        listOf(
                            mine,
                            other.copy(
                                id = "legacy", householdId = null, sharing = ItemSharing.SHARED))))
      }
    }
    badge("mine").assertTextEquals("Personal")
    owner("mine").assertTextEquals("Owner: You")
    badge("legacy").assertTextEquals("Shared")
    owner("legacy").assertTextEquals("Owner: Unknown owner")
    compose.onAllNodesWithText("email@example.com", substring = true).assertCountEquals(0)
    compose.onNodeWithTag(C.Tag.fridge_type).assertTextEquals("Fridge type: Private")
  }
}
