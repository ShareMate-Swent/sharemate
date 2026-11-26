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
import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.recipes.RecipeRepository
import com.android.sharemate.model.recipes.RecipeRequestResult
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
