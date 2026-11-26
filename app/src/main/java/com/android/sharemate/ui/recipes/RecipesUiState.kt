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
) {
  init {
    require(matchingIngredientCount >= 0 && missingIngredientCount >= 0) {
      "Ingredient counts must be non-negative"
    }
  }
}

/** Presentation reasons supplied by the caller; no network or offline policy is defined here. */
enum class RecipesErrorReason {
  OFFLINE,
  SERVICE,
}

/** Explicit request states. [Success] with an empty list renders the empty state. */
sealed interface RecipesUiState {
  /** No request has been made; opening the Recipes tab alone must not fetch data. */
  data object Idle : RecipesUiState

  data object Loading : RecipesUiState

  data class Success(val recipes: List<RecipeCardData>) : RecipesUiState

  data class Error(val reason: RecipesErrorReason) : RecipesUiState
}
