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
  private val mutableSelectedOption = MutableStateFlow<RecipePresentationOption?>(null)
  /** Presentation choice retained with this graph-scoped ViewModel across configuration changes. */
  val selectedOption = mutableSelectedOption.asStateFlow()
  private var defaultRecipes: List<RecipeCardData> = emptyList()

  /** Changes local presentation only; null restores the untouched default response order. */
  fun selectOption(option: RecipePresentationOption?) {
    if (mutableSelectedOption.value == option) return
    mutableSelectedOption.value = option
    if (mutableState.value is RecipesUiState.Success) {
      mutableState.value = RecipesUiState.Success(present(defaultRecipes))
    }
  }

  private fun present(recipes: List<RecipeCardData>) =
      mutableSelectedOption.value?.apply(recipes) ?: recipes

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
                  is RecipeRequestResult.Success -> {
                    defaultRecipes =
                        result.recipes.map {
                          RecipeCardData(
                              it.id,
                              it.title,
                              it.imageUrl,
                              it.matchingIngredientCount,
                              it.missingIngredientCount)
                        }
                    RecipesUiState.Success(present(defaultRecipes))
                  }
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
