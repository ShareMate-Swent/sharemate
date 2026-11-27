// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.recipes

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.recipes.FakeRecipeRepository
import com.android.sharemate.model.recipes.RecipeRepository
import com.android.sharemate.model.recipes.RecipeRequestResult
import com.android.sharemate.model.recipes.RecipeSuggestion
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RecipeOptionsBarTest {
  @get:Rule val compose = createComposeRule()
  private val store = ViewModelStore()

  @After
  fun tearDown() {
    compose.runOnIdle { store.clear() }
  }

  @Test
  fun registryDefinesEveryChipAndBothWaysToClearReachTheViewModel() {
    var calls = 0
    val fake =
        FakeRecipeRepository(
            RecipeRequestResult.Success(
                listOf(
                    RecipeSuggestion("z", "Zucchini", null, 1, 0),
                    RecipeSuggestion("a", "Apple", null, 1, 1))),
            delayMillis = 0)
    val vm =
        RecipesViewModel(
            RecipeRepository {
              calls++
              fake.suggestRecipes()
            })
    store.put("recipes", vm)
    compose.setContent { MaterialTheme { RecipesScreen(vm) } }
    compose.runOnIdle { vm.requestRecipes() }
    compose.onNodeWithTag(RecipeOptionsTestTags.BAR).assertIsDisplayed()
    compose.onNodeWithTag(RecipeOptionsTestTags.CLEAR).assertIsNotEnabled()
    RecipePresentationOption.entries.forEach { option ->
      compose
          .onNodeWithTag(RecipeOptionsTestTags.option(option))
          .assertTextEquals(RuntimeEnvironment.getApplication().getString(option.label))
          .assertIsNotSelected()
          .performClick()
          .assertIsSelected()
      compose.runOnIdle { assertEquals(option, vm.selectedOption.value) }
    }
    compose.onNodeWithTag(RecipeOptionsTestTags.CLEAR).performClick()
    RecipePresentationOption.entries.forEach {
      compose.onNodeWithTag(RecipeOptionsTestTags.option(it)).assertIsNotSelected()
    }
    val first = RecipePresentationOption.entries.first()
    compose
        .onNodeWithTag(RecipeOptionsTestTags.option(first))
        .performClick()
        .assertIsSelected()
        .performClick()
        .assertIsNotSelected()
    compose.runOnIdle {
      assertNull(vm.selectedOption.value)
      assertEquals(
          listOf("z", "a"), (vm.uiState.value as RecipesUiState.Success).recipes.map { it.id })
      assertEquals(1, calls)
    }
  }

  @Test
  fun emptyListKeepsOptionsUsableWithoutHidingTheEmptyMessage() {
    val vm = RecipesViewModel(FakeRecipeRepository(delayMillis = 0))
    store.put("recipes", vm)
    compose.setContent { MaterialTheme { RecipesScreen(vm) } }
    compose.runOnIdle { vm.requestRecipes() }
    compose
        .onNodeWithTag(RecipeOptionsTestTags.option(RecipePresentationOption.entries.first()))
        .performClick()
    compose.onNodeWithTag(RecipesTestTags.EMPTY).assertIsDisplayed()
    compose.onNodeWithTag(RecipeOptionsTestTags.CLEAR).performClick()
    compose.onNodeWithTag(RecipesTestTags.EMPTY).assertIsDisplayed()
  }

  @Test
  fun idleLoadingAndErrorKeepTheirContentWithoutAnOptionsBar() {
    val state = mutableStateOf<RecipesUiState>(RecipesUiState.Idle)
    compose.setContent { MaterialTheme { RecipesContent(state.value, {}) } }
    for ((value, tag) in
        listOf(
            RecipesUiState.Idle to RecipesTestTags.IDLE,
            RecipesUiState.Loading to RecipesTestTags.LOADING,
            RecipesUiState.Error(RecipesErrorReason.OFFLINE) to RecipesTestTags.ERROR,
            RecipesUiState.Error(RecipesErrorReason.SERVICE) to RecipesTestTags.ERROR)) {
      compose.runOnIdle { state.value = value }
      compose.onNodeWithTag(tag).assertIsDisplayed()
      compose.onNodeWithTag(RecipeOptionsTestTags.BAR).assertDoesNotExist()
    }
  }
}
