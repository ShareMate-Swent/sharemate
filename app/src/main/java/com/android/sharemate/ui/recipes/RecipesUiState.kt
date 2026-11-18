// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.recipes

/**
 * Presentation data, not the service model from #338. IDs must be stable and unique within a list;
 * ingredient counts are supplied by the caller, not calculated by this UI.
 */
data class RecipeCardData(
    val id: String,
    val title: String,
    val imageUrl: String?,
    val matchingIngredientCount: Int,
    val missingIngredientCount: Int,
)

/** Explicit request states. [Success] with an empty list renders the empty state. */
sealed interface RecipesUiState {
  data object Loading : RecipesUiState

  data class Success(val recipes: List<RecipeCardData>) : RecipesUiState

  data object Error : RecipesUiState
}
