// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.recipes

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.SuccessResult
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-rUS-w360dp-h800dp")
class RecipesPresentationTest {
  @get:Rule val compose = createComposeRule()
  private val requests = CopyOnWriteArrayList<PendingImage>()
  private val loader =
      ImageLoader.Builder(RuntimeEnvironment.getApplication())
          .memoryCache(null)
          .diskCache(null)
          .components {
            add(
                Interceptor { chain ->
                  val pending = PendingImage(chain.request.data.toString())
                  requests += pending
                  if (pending.success.await())
                      SuccessResult(ColorDrawable(Color.RED), chain.request, DataSource.MEMORY)
                  else ErrorResult(null, chain.request, IOException("Controlled image failure"))
                })
          }
          .build()
  private val soup = RecipeCardData("soup", "Vegetable soup", null, 2, 1)
  private val state = mutableStateOf<RecipesUiState>(RecipesUiState.Loading)
  private var retries = 0

  @After
  fun tearDown() {
    loader.shutdown()
  }

  private fun render() {
    compose.setContent {
      MaterialTheme { RecipesContent(state.value, { retries++ }, imageLoader = loader) }
    }
  }

  @Test
  fun loadingHasAnAccessibleIndeterminateProgressIndicator() {
    render()
    compose
        .onNodeWithTag(RecipesTestTags.LOADING)
        .assertIsDisplayed()
        .assertContentDescriptionEquals("Loading recipe suggestions")
        .assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
    compose.onNodeWithTag(RecipesTestTags.LIST).assertDoesNotExist()
    compose.onNodeWithTag(RecipesTestTags.RETRY).assertDoesNotExist()
    assertTrue(requests.isEmpty())
  }

  @Test
  fun emptySuccessShowsMeaningfulMessageWithoutListOrRetry() {
    state.value = RecipesUiState.Success(emptyList())
    render()
    compose
        .onNodeWithTag(RecipesTestTags.EMPTY)
        .assertIsDisplayed()
        .assertTextEquals("No recipes found for these ingredients.")
    compose.onNodeWithTag(RecipesTestTags.LIST).assertDoesNotExist()
    compose.onNodeWithTag(RecipesTestTags.LOADING).assertDoesNotExist()
    compose.onNodeWithTag(RecipesTestTags.RETRY).assertDoesNotExist()
  }

  @Test
  fun errorRemovesPreviousCardsAndRetryOnlyInvokesTheCallback() {
    state.value = RecipesUiState.Success(listOf(soup))
    render()
    compose.onNodeWithTag(RecipesTestTags.card(soup.id)).assertIsDisplayed()
    compose.runOnIdle { state.value = RecipesUiState.Error }
    compose.onNodeWithTag(RecipesTestTags.card(soup.id)).assertDoesNotExist()
    compose.onNodeWithTag(RecipesTestTags.LIST).assertDoesNotExist()
    compose
        .onNodeWithTag(RecipesTestTags.ERROR)
        .assertTextEquals("Recipes could not be loaded. Please try again.")
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    compose.onNodeWithTag(RecipesTestTags.RETRY).assertHasClickAction().performClick()
    compose.runOnIdle {
      assertEquals(1, retries)
      assertEquals(RecipesUiState.Error, state.value)
      state.value = RecipesUiState.Loading
    }
    compose.onNodeWithTag(RecipesTestTags.LOADING).assertIsDisplayed()
    compose.onNodeWithTag(RecipesTestTags.ERROR).assertDoesNotExist()
  }

  @Test
  fun callerOrderAndSingularPluralAndZeroCountsArePreserved() {
    val first = soup.copy(id = "z", title = "Zucchini soup")
    val second =
        soup.copy(
            id = "a",
            title = "Apple salad",
            matchingIngredientCount = 1,
            missingIngredientCount = 0)
    state.value = RecipesUiState.Success(listOf(first, second))
    render()
    compose
        .onNodeWithTag(RecipesTestTags.matching("z"), true)
        .assertTextEquals("2 matching ingredients")
    compose
        .onNodeWithTag(RecipesTestTags.missing("z"), true)
        .assertTextEquals("1 missing ingredient")
    compose
        .onNodeWithTag(RecipesTestTags.matching("a"), true)
        .assertTextEquals("1 matching ingredient")
    compose
        .onNodeWithTag(RecipesTestTags.missing("a"), true)
        .assertTextEquals("0 missing ingredients")
    val firstBounds =
        compose.onNodeWithTag(RecipesTestTags.card("z")).fetchSemanticsNode().boundsInRoot
    val secondBounds =
        compose.onNodeWithTag(RecipesTestTags.card("a")).fetchSemanticsNode().boundsInRoot
    assertTrue(firstBounds.top < secondBounds.top)
    compose.onNodeWithTag(RecipesTestTags.card("z")).assertHasNoClickAction()
  }

  @Test
  fun missingOrBlankImagesUseAccessibleFallbackWithoutRequests() {
    state.value = RecipesUiState.Success(listOf(soup))
    render()
    compose
        .onNodeWithTag(RecipesTestTags.placeholder(soup.id), true)
        .assertIsDisplayed()
        .assertContentDescriptionEquals("No image available for Vegetable soup")
    compose.runOnIdle { state.value = RecipesUiState.Success(listOf(soup.copy(imageUrl = " \t"))) }
    compose.onNodeWithTag(RecipesTestTags.placeholder(soup.id), true).assertIsDisplayed()
    compose.onNodeWithTag(RecipesTestTags.image(soup.id), true).assertDoesNotExist()
    assertTrue(requests.isEmpty())
  }

  @Test
  fun failedImageShowsFallbackAndANewUrlCanRecover() {
    val withImage = soup.copy(imageUrl = "https://fixture.invalid/broken")
    state.value = RecipesUiState.Success(listOf(withImage))
    render()
    await { requests.size == 1 }
    compose.runOnIdle { requests[0].success.complete(false) }
    await {
      compose
          .onAllNodesWithTag(RecipesTestTags.placeholder(soup.id), true)
          .fetchSemanticsNodes()
          .size == 1
    }
    compose.onNodeWithTag(RecipesTestTags.placeholder(soup.id), true).assertIsDisplayed()
    compose.runOnIdle {
      state.value =
          RecipesUiState.Success(listOf(withImage.copy(imageUrl = "https://fixture.invalid/valid")))
    }
    await { requests.size == 2 }
    compose.runOnIdle { requests[1].success.complete(true) }
    compose
        .onNodeWithTag(RecipesTestTags.image(soup.id), true)
        .assertIsDisplayed()
        .assertContentDescriptionEquals("Photo of Vegetable soup")
    compose.onNodeWithTag(RecipesTestTags.placeholder(soup.id), true).assertDoesNotExist()
    assertEquals(
        listOf("https://fixture.invalid/broken", "https://fixture.invalid/valid"),
        requests.map { it.url })
  }

  @Test
  fun stableIdsKeepImageFailureWithItsRecipeWhenANewCardIsInserted() {
    val first = soup.copy(imageUrl = "https://fixture.invalid/same")
    state.value = RecipesUiState.Success(listOf(first))
    render()
    await { requests.size == 1 }
    compose.runOnIdle { requests[0].success.complete(false) }
    await {
      compose
          .onAllNodesWithTag(RecipesTestTags.placeholder(first.id), true)
          .fetchSemanticsNodes()
          .size == 1
    }
    val inserted = first.copy(id = "new", title = "New recipe")
    compose.runOnIdle { state.value = RecipesUiState.Success(listOf(inserted, first)) }
    await { requests.size == 2 }
    compose.runOnIdle { requests[1].success.complete(true) }
    compose.onNodeWithTag(RecipesTestTags.placeholder(first.id), true).assertExists()
    compose.onNodeWithTag(RecipesTestTags.image(inserted.id), true).assertExists()
    compose.onNodeWithTag(RecipesTestTags.placeholder(inserted.id), true).assertDoesNotExist()
    assertEquals(2, requests.size)
  }

  @Test
  fun updatedCardsAndTransitionsDoNotKeepOldContent() {
    state.value = RecipesUiState.Success(listOf(soup))
    render()
    val updated =
        soup.copy(title = "Updated soup", matchingIngredientCount = 5, missingIngredientCount = 3)
    compose.runOnIdle { state.value = RecipesUiState.Success(listOf(updated)) }
    compose.onAllNodesWithTag(RecipesTestTags.card(soup.id)).assertCountEquals(1)
    compose.onNodeWithTag(RecipesTestTags.title(soup.id), true).assertTextEquals("Updated soup")
    compose.onNodeWithText(soup.title, useUnmergedTree = true).assertDoesNotExist()
    compose
        .onNodeWithTag(RecipesTestTags.matching(soup.id), true)
        .assertTextEquals("5 matching ingredients")
    compose
        .onNodeWithTag(RecipesTestTags.missing(soup.id), true)
        .assertTextEquals("3 missing ingredients")
    compose.runOnIdle { state.value = RecipesUiState.Success(emptyList()) }
    compose.onNodeWithTag(RecipesTestTags.card(soup.id)).assertDoesNotExist()
    compose.onNodeWithTag(RecipesTestTags.EMPTY).assertIsDisplayed()
    compose.runOnIdle { state.value = RecipesUiState.Success(listOf(soup)) }
    compose.onNodeWithTag(RecipesTestTags.EMPTY).assertDoesNotExist()
    compose.onNodeWithTag(RecipesTestTags.title(soup.id), true).assertTextEquals(soup.title)
  }

  @Test
  fun listCanScrollToRecipesBeyondTheFirstViewport() {
    state.value =
        RecipesUiState.Success((0..14).map { soup.copy(id = "$it", title = "Recipe $it") })
    render()
    compose.onNodeWithTag(RecipesTestTags.LIST).performScrollToIndex(14)
    compose
        .onNodeWithTag(RecipesTestTags.title("14"), true)
        .assertIsDisplayed()
        .assertTextEquals("Recipe 14")
  }

  @Test
  fun longTitleAndDoubleFontSizeKeepFullAccessibleTextAndReadableCounts() {
    val title =
        "A long recipe title with seasonal vegetables, lentils and delicious homemade dressing"
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        MaterialTheme {
          Column(Modifier.verticalScroll(rememberScrollState())) {
            RecipeCard(soup.copy(title = title), imageLoader = loader)
          }
        }
      }
    }
    val heading = compose.onNodeWithTag(RecipesTestTags.title(soup.id), true)
    heading
        .assertTextEquals(title)
        .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    compose
        .onNodeWithTag(RecipesTestTags.missing(soup.id), true)
        .performScrollTo()
        .assertIsDisplayed()
    val cardBounds =
        compose.onNodeWithTag(RecipesTestTags.card(soup.id)).fetchSemanticsNode().boundsInRoot
    val textBounds = heading.fetchSemanticsNode().boundsInRoot
    assertTrue(textBounds.left >= cardBounds.left && textBounds.right <= cardBounds.right)
    compose.onNodeWithTag(RecipesTestTags.card(soup.id)).assertHasNoClickAction()
  }

  private fun await(condition: () -> Boolean) {
    compose.waitUntil(5_000) {
      compose.waitForIdle()
      condition()
    }
  }

  private data class PendingImage(
      val url: String,
      val success: CompletableDeferred<Boolean> = CompletableDeferred()
  )
}
