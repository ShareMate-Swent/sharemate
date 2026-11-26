// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.recipes

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeRecipeRepositoryTest {
  @Test
  fun returnsConfiguredSuccessOnlyAfterItsCancellableDelay() = runTest {
    val response =
        RecipeRequestResult.Success(listOf(RecipeSuggestion("demo", "Example", null, 2, 1)))
    val pending = async { FakeRecipeRepository(response).suggestRecipes() }
    runCurrent()
    assertFalse(pending.isCompleted)
    advanceTimeBy(399)
    assertFalse(pending.isCompleted)
    advanceTimeBy(1)
    runCurrent()
    assertEquals(response, pending.await())
  }

  @Test
  fun supportsEmptyAndErrorResponsesWithoutNetworkCalls() = runTest {
    for (response in
        listOf(
            RecipeRequestResult.Success(emptyList()),
            RecipeRequestResult.Offline,
            RecipeRequestResult.ServiceError)) {
      assertEquals(response, FakeRecipeRepository(response, delayMillis = 0).suggestRecipes())
    }
  }

  @Test
  fun cancellingStopsPendingDemoResponse() = runTest {
    val pending = async { FakeRecipeRepository().suggestRecipes() }
    runCurrent()
    pending.cancelAndJoin()
    assertTrue(pending.isCancelled)
  }

  @Test
  fun negativeDelayIsRejected() {
    assertThrows(IllegalArgumentException::class.java) { FakeRecipeRepository(delayMillis = -1) }
  }
}
