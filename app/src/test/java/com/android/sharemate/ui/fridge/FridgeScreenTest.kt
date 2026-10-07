// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.MainActivity
import com.android.sharemate.model.item.Item
import com.android.sharemate.resources.C
import com.android.sharemate.ui.navigation.NavigationTestTags
import com.android.sharemate.ui.theme.SampleAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FridgeScreenTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun eachItemExposesAnAccessibleRemoveActionForItsOwnId() {
    val requests = mutableListOf<String>()
    val items = listOf(Item(id = "milk", name = "Milk"), Item(id = "bread", name = "Bread"))
    composeTestRule.activity.setContent {
      SampleAppTheme(dynamicColor = false) {
        FridgeScreen(uiState = FridgeUiState(items = items), onRemoveItem = { requests += it })
      }
    }

    composeTestRule.onNodeWithText("Milk").assertIsDisplayed()
    composeTestRule.onNodeWithText("Bread").assertIsDisplayed()
    composeTestRule
        .onNodeWithContentDescription("Remove Milk")
        .assertIsDisplayed()
        .assertIsEnabled()
        .performClick()
    composeTestRule
        .onNodeWithContentDescription("Remove Bread")
        .assertIsDisplayed()
        .assertIsEnabled()
        .performClick()
    composeTestRule.runOnIdle { assertEquals(listOf("milk", "bread"), requests) }
    composeTestRule.onNodeWithTag(C.Tag.fridge_empty).assertDoesNotExist()
  }

  @Test
  fun removeActionIsDisabledWithoutRuntimeWiring() {
    composeTestRule.activity.setContent {
      SampleAppTheme(dynamicColor = false) {
        FridgeScreen(uiState = FridgeUiState(items = listOf(Item(id = "milk", name = "Milk"))))
      }
    }
    composeTestRule
        .onNodeWithContentDescription("Remove Milk")
        .assertIsDisplayed()
        .assertIsNotEnabled()
  }

  @Test
  fun removalActionsAreDisabledWhileDeletionIsPending() {
    composeTestRule.activity.setContent {
      SampleAppTheme(dynamicColor = false) {
        FridgeScreen(
            uiState =
                FridgeUiState(items = listOf(Item(id = "milk", name = "Milk")), isDeleting = true),
            onRemoveItem = {})
      }
    }
    composeTestRule.onNodeWithContentDescription("Remove Milk").assertIsNotEnabled()
    composeTestRule.onNodeWithTag(C.Tag.fridge_remove_dialog).assertDoesNotExist()
  }

  @Test
  fun callerModifierIsAppliedAndUpdatesWithoutChangingFridgeContent() {
    val modifier = mutableStateOf(Modifier.semantics { contentDescription = "Initial fridge" })
    composeTestRule.activity.setContent {
      SampleAppTheme(dynamicColor = false) { FridgeScreen(modifier = modifier.value) }
    }

    composeTestRule.onNodeWithContentDescription("Initial fridge").assertIsDisplayed()
    composeTestRule.runOnIdle {
      modifier.value = Modifier.semantics { contentDescription = "Updated fridge" }
    }

    composeTestRule.onNodeWithContentDescription("Initial fridge").assertDoesNotExist()
    composeTestRule.onNodeWithContentDescription("Updated fridge").assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.fridge_title).assertIsDisplayed().assertTextEquals("Fridge")
    composeTestRule
        .onNodeWithTag(C.Tag.fridge_empty)
        .assertIsDisplayed()
        .assertTextEquals("Your fridge is empty")
    composeTestRule.onNodeWithText("All").assertIsSelected().assertIsNotEnabled()
    listOf("Fruits", "Vegetables", "Canned goods").forEach { category ->
      composeTestRule.onNodeWithText(category).assertIsNotSelected().assertIsNotEnabled()
    }
  }

  @Test
  fun launcherDisplaysFridgeTitleAndEmptyState() {
    composeTestRule.onNodeWithTag(C.Tag.fridge_screen_container).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.fridge_title).assertIsDisplayed().assertTextEquals("Fridge")
    composeTestRule
        .onNodeWithTag(C.Tag.fridge_empty)
        .assertIsDisplayed()
        .assertTextEquals("Your fridge is empty")
  }

  @Test
  fun categoriesAreStaticWithAllSelected() {
    composeTestRule
        .onNodeWithText("All")
        .assertIsDisplayed()
        .assertIsSelected()
        .assertIsNotEnabled()
    listOf("Fruits", "Vegetables", "Canned goods").forEach { category ->
      composeTestRule
          .onNodeWithText(category)
          .performScrollTo()
          .assertIsDisplayed()
          .assertIsNotSelected()
          .assertIsNotEnabled()
    }
  }

  @Test
  fun sortAndOwnershipControlsAreStatic() {
    listOf("Sort", "Yours", "Shared").forEach { label ->
      composeTestRule.onNodeWithText(label).assertIsDisplayed().assertIsNotEnabled()
    }
  }

  @Test
  fun bottomBarShowsOnlyFridgeSelectedAndDestinationsEnabled() {
    listOf(
            NavigationTestTags.FRIDGE_TAB to "Fridge",
            NavigationTestTags.RECIPES_TAB to "Recipes",
            NavigationTestTags.RECEIPTS_TAB to "Receipts",
            NavigationTestTags.SETTINGS_TAB to "Settings")
        .forEach { (testTag, label) ->
          val destination = composeTestRule.onNodeWithTag(testTag)
          destination.assertIsDisplayed().assertTextEquals(label).assertIsEnabled()
          if (testTag == NavigationTestTags.FRIDGE_TAB) destination.assertIsSelected()
          else destination.assertIsNotSelected()
        }
  }

  @Test
  fun addItemIsVisibleButDisabledWithoutRuntimeWiring() {
    composeTestRule.onNodeWithTag(C.Tag.fridge_add_item).assertIsDisplayed().assertIsNotEnabled()
  }
}
