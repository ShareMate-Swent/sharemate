// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.recipes

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.recipes.RecipeRepository
import com.android.sharemate.model.recipes.RecipeRequestResult
import com.android.sharemate.model.recipes.RecipeSuggestion
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp")
class RecipesScreenFlowTest {
  @get:Rule val compose = createComposeRule()
  private val store = ViewModelStore()
  private val pending = CompletableDeferred<RecipeRequestResult>()
  private var calls = 0
  private val model =
      RecipesViewModel(
              RecipeRepository {
                calls++
                pending.await()
              })
          .also { store.put("recipes", it) }

  @After
  fun tearDown() {
    compose.runOnIdle { store.clear() }
  }

  @Test
  fun realScreenUsesStableViewModelOrderAndDisplaysMissingNamesOnEachCard() {
    val input =
        listOf(
            RecipeSuggestion("many", "Many", null, 1, 3, listOf("Salt", "Onion", "Pepper")),
            RecipeSuggestion("full", "Full", null, 3, 0),
            RecipeSuggestion("first-one", "First one", null, 2, 1, listOf("Milk")),
            RecipeSuggestion("second-one", "Second one", null, 2, 1, listOf("Egg")))
    compose.setContent { MaterialTheme { RecipesScreen(model) } }
    compose.runOnIdle {
      model.requestRecipes()
      pending.complete(RecipeRequestResult.Success(input))
    }
    val expected = listOf(input[1], input[2], input[3], input[0])
    expected.forEachIndexed { index, recipe ->
      compose.onNodeWithTag(RecipesTestTags.LIST).performScrollToIndex(index)
      compose
          .onNodeWithTag(RecipesTestTags.title(recipe.id), true)
          .assertIsDisplayed()
          .assertTextEquals(recipe.title)
      if (recipe.missingIngredients.isNotEmpty()) {
        compose
            .onNodeWithTag(RecipesTestTags.missingNames(recipe.id), true)
            .assertIsDisplayed()
            .assertTextEquals("Missing: " + recipe.missingIngredients.joinToString(", "))
      }
    }
    compose.runOnIdle { assertEquals(1, calls) }
  }

  @Test
  fun changingScreenInputsDoesNotRequestAgainDuringLoadingOrAfterCompletion() {
    val version = mutableStateOf(0)
    compose.setContent {
      MaterialTheme {
        val currentVersion = version.value
        RecipesScreen(
            model, modifier = Modifier.semantics { contentDescription = "Version $currentVersion" })
      }
    }
    compose.onNodeWithTag(RecipesTestTags.IDLE).assertIsDisplayed()
    compose.runOnIdle {
      assertEquals(0, calls)
      model.requestRecipes()
    }
    compose.onNodeWithTag(RecipesTestTags.LOADING).assertIsDisplayed()
    compose.runOnIdle { version.value++ }
    compose.onNodeWithTag(RecipesTestTags.LOADING).assertIsDisplayed()
    compose.runOnIdle {
      assertEquals(1, calls)
      pending.complete(RecipeRequestResult.Success(emptyList()))
    }
    compose.onNodeWithTag(RecipesTestTags.EMPTY).assertIsDisplayed()
    compose.runOnIdle { version.value++ }
    compose.onNodeWithTag(RecipesTestTags.EMPTY).assertIsDisplayed()
    compose.runOnIdle { assertEquals(1, calls) }
  }

  @Test
  fun demoSourceIsClearlyLabelledAndDoesNotFetchOnScreenEntry() {
    compose.setContent { MaterialTheme { RecipesScreen(model, isDemo = true) } }
    compose
        .onNodeWithTag(RecipesTestTags.DEMO_NOTICE)
        .assertIsDisplayed()
        .assertTextEquals("Demo suggestions — not connected to your fridge yet.")
    compose.onNodeWithTag(RecipesTestTags.IDLE).assertIsDisplayed()
    compose.runOnIdle { assertEquals(0, calls) }
  }
}
