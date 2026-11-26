// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate

import android.content.ComponentName
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStoreOwner
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.recipes.*
import com.android.sharemate.resources.C
import com.android.sharemate.ui.household.HouseholdGateViewModel
import com.android.sharemate.ui.navigation.NavigationTestTags
import com.android.sharemate.ui.recipes.RecipesTestTags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Same MainActivity wiring, with only the composition-root recipe source replaced. */
class RecipeRequestTestActivity : MainActivity() {
  override fun createRecipeRepository(): RecipeRepository = RecipeNavigationFixture.repository
}

private object RecipeNavigationFixture {
  val pending = mutableListOf<CompletableDeferred<RecipeRequestResult>>()
  var cancelled = 0
  val repository = RecipeRepository {
    val response = CompletableDeferred<RecipeRequestResult>()
    pending += response
    try {
      response.await()
    } catch (e: CancellationException) {
      cancelled++
      throw e
    }
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp")
class RecipeRequestNavigationTest {
  @get:Rule(order = 0)
  val firebase = FirebaseAuthTestRule(startSignedIn = true, pendingHouseholdLookup = true)
  @get:Rule(order = 1)
  val fixture =
      object : ExternalResource() {
        override fun before() {
          RecipeNavigationFixture.pending.clear()
          RecipeNavigationFixture.cancelled = 0
          val app = RuntimeEnvironment.getApplication()
          shadowOf(app.packageManager)
              .addActivityIfNotPresent(ComponentName(app, RecipeRequestTestActivity::class.java))
        }
      }
  @get:Rule(order = 2) val compose = createAndroidComposeRule<RecipeRequestTestActivity>()
  private val soup = RecipeSuggestion("soup", "Test soup", null, 2, 1)

  private fun resolve() {
    compose.runOnIdle { firebase.resolveInitialHousehold() }
    awaitFridge()
  }

  private fun awaitFridge() {
    // Household activation now performs IO; Compose idleness alone does not await its result.
    compose.waitUntil(timeoutMillis = 5_000) {
      compose.onAllNodesWithTag(C.Tag.fridge_suggest_recipes).fetchSemanticsNodes().size == 1
    }
    compose.onNodeWithTag(C.Tag.fridge_suggest_recipes).assertIsDisplayed()
  }

  private fun request() {
    compose.onNodeWithTag(C.Tag.fridge_suggest_recipes).performClick()
  }

  private fun tab(tag: String) {
    compose.onNodeWithTag(tag).performClick()
  }

  private fun complete(result: RecipeRequestResult) {
    compose.runOnIdle { RecipeNavigationFixture.pending.last().complete(result) }
  }

  private fun assertCalls(count: Int) {
    compose.runOnIdle { assertEquals(count, RecipeNavigationFixture.pending.size) }
  }

  @Test
  fun buttonNavigatesAndRecompositionTabReturnAndRecreationDoNotRepeatRequest() {
    resolve()
    tab(NavigationTestTags.RECIPES_TAB)
    compose.onNodeWithTag(RecipesTestTags.IDLE).assertIsDisplayed()
    assertCalls(0)
    tab(NavigationTestTags.FRIDGE_TAB)
    request()
    compose.onNodeWithTag(NavigationTestTags.RECIPES_TAB).assertIsSelected()
    compose.onNodeWithTag(RecipesTestTags.LOADING).assertIsDisplayed()
    assertCalls(1)
    compose.runOnIdle {
      firebase.emitItems(
          "student-home",
          listOf(
              Item(
                  id = "milk",
                  name = "Milk",
                  ownerId = "trusted-student-id",
                  householdId = "student-home")))
    }
    assertCalls(1)
    complete(RecipeRequestResult.Success(listOf(soup)))
    compose.onNodeWithTag(RecipesTestTags.card("soup")).assertIsDisplayed()
    tab(NavigationTestTags.FRIDGE_TAB)
    compose.onNodeWithText("Milk").assertExists()
    tab(NavigationTestTags.RECIPES_TAB)
    compose.onNodeWithTag(RecipesTestTags.card("soup")).assertIsDisplayed()
    compose.activityRule.scenario.recreate()
    compose.onNodeWithTag(RecipesTestTags.card("soup")).assertIsDisplayed()
    assertCalls(1)
    assertEquals(listOf("student-home"), firebase.observedHouseholds)
    assertTrue(firebase.removedListeners.isEmpty())
  }

  @Test
  fun anotherButtonPressMakesOneNewRequestAndCancelsPreviousOne() {
    resolve()
    request()
    tab(NavigationTestTags.FRIDGE_TAB)
    request()
    assertCalls(2)
    compose.runOnIdle { assertEquals(1, RecipeNavigationFixture.cancelled) }
    complete(RecipeRequestResult.Success(emptyList()))
    compose.onNodeWithTag(RecipesTestTags.EMPTY).assertIsDisplayed()
    tab(NavigationTestTags.FRIDGE_TAB)
    tab(NavigationTestTags.RECIPES_TAB)
    compose.onNodeWithTag(RecipesTestTags.EMPTY).assertIsDisplayed()
    assertCalls(2)
  }

  @Test
  fun errorAndRetryUseTheSameRequestFlow() {
    resolve()
    request()
    complete(RecipeRequestResult.Offline)
    compose.onNodeWithTag(RecipesTestTags.ERROR).assertIsDisplayed()
    compose.onNodeWithTag(RecipesTestTags.RETRY).performClick()
    compose.onNodeWithTag(RecipesTestTags.LOADING).assertIsDisplayed()
    assertCalls(2)
    complete(RecipeRequestResult.ServiceError)
    compose.onNodeWithTag(RecipesTestTags.ERROR).assertIsDisplayed()
    tab(NavigationTestTags.FRIDGE_TAB)
    tab(NavigationTestTags.RECIPES_TAB)
    assertCalls(2)
  }

  @Test
  fun selectedFridgeSortOrderSurvivesButtonNavigation() {
    resolve()
    compose
        .onNodeWithText(
            compose.activity.getString(
                R.string.fridge_sort_selected,
                compose.activity.getString(R.string.fridge_sort_expiration_date)))
        .performClick()
    compose.onNodeWithText(compose.activity.getString(R.string.fridge_sort_name)).performClick()
    request()
    tab(NavigationTestTags.FRIDGE_TAB)
    compose
        .onNodeWithText(
            compose.activity.getString(
                R.string.fridge_sort_selected,
                compose.activity.getString(R.string.fridge_sort_name)))
        .performClick()
    compose.onNodeWithText(compose.activity.getString(R.string.fridge_sort_name)).assertIsSelected()
    assertEquals(listOf("student-home"), firebase.observedHouseholds)
  }

  @Test
  fun sameUserHouseholdSwitchCancelsOldRequestAndStartsIdle() {
    resolve()
    request()
    compose.onNodeWithTag(RecipesTestTags.LOADING).assertIsDisplayed()
    assertCalls(1)
    compose.runOnIdle {
      firebase.setHousehold("trusted-student-id", "other-home")
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
    awaitFridge()
    tab(NavigationTestTags.RECIPES_TAB)
    compose.onNodeWithTag(RecipesTestTags.IDLE).assertIsDisplayed()
    assertCalls(1)
    compose.waitUntil(timeoutMillis = 5_000) { RecipeNavigationFixture.cancelled == 1 }
    compose.runOnIdle {
      assertEquals(1, RecipeNavigationFixture.cancelled)
      assertEquals("other-home", firebase.observedHouseholds.last())
    }
  }

  @Test
  fun signOutCancelsPendingRequestAndSameUserSignsBackInIdle() {
    resolve()
    request()
    compose.onNodeWithTag(RecipesTestTags.LOADING).assertIsDisplayed()
    compose.runOnIdle { firebase.setSession(null) }
    compose.onNodeWithTag(C.Tag.fridge_suggest_recipes).assertDoesNotExist()
    compose.onNodeWithTag(RecipesTestTags.LOADING).assertDoesNotExist()
    compose.runOnIdle { assertEquals(1, RecipeNavigationFixture.cancelled) }
    compose.runOnIdle {
      firebase.setHousehold("trusted-student-id", "student-home")
      firebase.setSession("trusted-student-id")
    }
    awaitFridge()
    tab(NavigationTestTags.RECIPES_TAB)
    compose.onNodeWithTag(RecipesTestTags.IDLE).assertIsDisplayed()
    assertCalls(1)
    compose.runOnIdle { assertEquals(1, RecipeNavigationFixture.cancelled) }
  }

  @Test
  fun householdChangeCancelsOldRequestAndNewHouseholdStartsIdle() {
    resolve()
    request()
    compose.runOnIdle {
      firebase.setHousehold("other-user", "other-home")
      firebase.setSession("other-user")
    }
    awaitFridge()
    tab(NavigationTestTags.RECIPES_TAB)
    compose.onNodeWithTag(RecipesTestTags.IDLE).assertIsDisplayed()
    assertCalls(1)
    compose.runOnIdle { assertEquals(1, RecipeNavigationFixture.cancelled) }
  }
}
