// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.recipes

import kotlinx.coroutines.delay

/** Explicit demo source for #341, independent of the fridge, Firebase and external services. */
class FakeRecipeRepository(
    private val result: RecipeRequestResult = RecipeRequestResult.Success(emptyList()),
    private val delayMillis: Long = 400L,
) : RecipeRepository {
  init {
    require(delayMillis >= 0) { "Delay must be non-negative" }
  }

  override suspend fun suggestRecipes(): RecipeRequestResult {
    delay(delayMillis)
    return result
  }
}
