// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.resources.C
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp")
class FridgeSuggestRecipesTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun buttonIsVisibleEvenForAnEmptyFridgeAndOnlyClicksInvokeCallback() {
    val state = mutableStateOf(FridgeUiState(fridgeType = FridgeType.SHARED))
    var clicks = 0
    compose.setContent {
      MaterialTheme { FridgeScreen(state.value, emptyMap(), onSuggestRecipes = { clicks++ }) }
    }
    compose
        .onNodeWithTag(C.Tag.fridge_suggest_recipes)
        .assertIsDisplayed()
        .assertTextEquals("Suggest Recipes")
    compose.runOnIdle {
      assertEquals(0, clicks)
      state.value = state.value.copy(fridgeType = FridgeType.PRIVATE, isLoading = true)
    }
    compose.onNodeWithTag(C.Tag.fridge_suggest_recipes).assertIsDisplayed().performClick()
    compose.runOnIdle { assertEquals(1, clicks) }
  }
}
