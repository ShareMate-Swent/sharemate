// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.recipes

/** Minimal suggestion data; ingredient counts are supplied by the repository, not the UI. */
data class RecipeSuggestion(
    val id: String,
    val title: String,
    val imageUrl: String?,
    val matchingIngredientCount: Int,
    val missingIngredientCount: Int,
)

/** Service failures are values; cancellation must propagate rather than become an error. */
sealed interface RecipeRequestResult {
  data class Success(val recipes: List<RecipeSuggestion>) : RecipeRequestResult

  data object Offline : RecipeRequestResult

  data object ServiceError : RecipeRequestResult
}

/**
 * Request boundary for #341. A real implementation in #338 can obtain available ingredients
 * via #339 internally, without making the ViewModel depend on inventory or a service client.
 */
fun interface RecipeRepository {
  suspend fun suggestRecipes(): RecipeRequestResult
}
