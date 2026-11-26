// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.recipes.RecipeRepository
import com.android.sharemate.model.recipes.RecipeRequestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Shared by the fridge action and Recipes destination; only explicit actions start requests. */
class RecipesViewModel(private val repository: RecipeRepository) : ViewModel() {
  private val mutableState = MutableStateFlow<RecipesUiState>(RecipesUiState.Idle)
  val uiState = mutableState.asStateFlow()
  private var request: Job? = null

  /** A new press replaces an older request; an obsolete response cannot overwrite its result. */
  fun requestRecipes() {
    request?.cancel()
    mutableState.value = RecipesUiState.Loading
    request =
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
          try {
            val result = repository.suggestRecipes()
            ensureActive()
            mutableState.value =
                when (result) {
                  is RecipeRequestResult.Success ->
                      RecipesUiState.Success(
                          // sortedBy is stable: complete matches first, then fewer missing;
                          // equal counts preserve the repository's original relative order.
                          result.recipes
                              .sortedBy { it.missingIngredientCount }
                              .map {
                                RecipeCardData(
                                    it.id,
                                    it.title,
                                    it.imageUrl,
                                    it.matchingIngredientCount,
                                    it.missingIngredientCount,
                                    it.missingIngredients)
                              })
                  RecipeRequestResult.Offline -> RecipesUiState.Error(RecipesErrorReason.OFFLINE)
                  RecipeRequestResult.ServiceError ->
                      RecipesUiState.Error(RecipesErrorReason.SERVICE)
                }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            // Defensive boundary for a faulty repository; cancellation still takes precedence.
            ensureActive()
            mutableState.value = RecipesUiState.Error(RecipesErrorReason.SERVICE)
          }
        }
  }
}
