// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.recipes

import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.recipes.RecipeRepository
import com.android.sharemate.model.recipes.RecipeRequestResult
import com.android.sharemate.model.recipes.RecipeSuggestion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeOptionsViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val store = ViewModelStore()
  // Equal missing counts isolate option behavior from the independent default-ranking policy.
  private val input =
      listOf(
          RecipeSuggestion("z", "Zucchini", null, 2, 1),
          RecipeSuggestion("a", "apple", null, 1, 1),
          RecipeSuggestion("a2", "APPLE", null, 3, 1))
  private var calls = 0
  private var response: RecipeRequestResult = RecipeRequestResult.Success(input)

  @Before fun setUp() = Dispatchers.setMain(dispatcher)

  @After
  fun tearDown() {
    store.clear()
    Dispatchers.resetMain()
  }

  private fun model(
      repository: RecipeRepository = RecipeRepository {
        calls++
        response
      }
  ) = RecipesViewModel(repository).also { store.put("recipes", it) }

  private fun ids(vm: RecipesViewModel) =
      (vm.uiState.value as RecipesUiState.Success).recipes.map { it.id }

  @Test
  fun initialChoiceIsDefaultAndDoesNotRequest() = runTest {
    val vm = model()
    assertNull(vm.selectedOption.value)
    assertEquals(RecipesUiState.Idle, vm.uiState.value)
    assertEquals(0, calls)
  }

  @Test
  fun selectSwitchAndClearUseStableCopiesWithoutRefetching() = runTest {
    val vm = model()
    vm.requestRecipes()
    assertEquals(listOf("z", "a", "a2"), ids(vm))
    vm.selectOption(RecipePresentationOption.TITLE_ASCENDING)
    assertEquals(listOf("a", "a2", "z"), ids(vm))
    vm.selectOption(RecipePresentationOption.TITLE_DESCENDING)
    assertEquals(listOf("z", "a", "a2"), ids(vm))
    vm.selectOption(null)
    assertNull(vm.selectedOption.value)
    assertEquals(listOf("z", "a", "a2"), ids(vm))
    assertEquals(listOf("z", "a", "a2"), input.map { it.id })
    assertEquals(1, calls)
  }

  @Test
  fun clearingRestoresFullMatchDefaultPresentationInsteadOfAlphabeticalOrder() = runTest {
    val default =
        listOf(
            RecipeSuggestion("z", "Zucchini", null, 2, 0),
            RecipeSuggestion("b", "Beans", null, 2, 0),
            RecipeSuggestion("a", "Apple", null, 2, 1),
            RecipeSuggestion("c", "Carrot", null, 2, 2))
    response = RecipeRequestResult.Success(default)
    val vm = model()
    vm.requestRecipes()
    assertEquals(listOf("z", "b", "a", "c"), ids(vm))
    vm.selectOption(RecipePresentationOption.TITLE_ASCENDING)
    assertEquals(listOf("a", "b", "c", "z"), ids(vm))
    vm.selectOption(RecipePresentationOption.TITLE_DESCENDING)
    assertEquals(listOf("z", "c", "b", "a"), ids(vm))
    vm.selectOption(null)
    assertEquals(listOf("z", "b", "a", "c"), ids(vm))
    assertEquals(1, calls)
  }

  @Test
  fun selectionDuringLoadingAppliesWhenTheResponseArrives() = runTest {
    val pending = CompletableDeferred<RecipeRequestResult>()
    val vm =
        model(
            RecipeRepository {
              calls++
              pending.await()
            })
    vm.requestRecipes()
    vm.selectOption(RecipePresentationOption.TITLE_ASCENDING)
    assertEquals(RecipesUiState.Loading, vm.uiState.value)
    pending.complete(RecipeRequestResult.Success(input))
    runCurrent()
    assertEquals(listOf("a", "a2", "z"), ids(vm))
    assertEquals(1, calls)
  }

  @Test
  fun clearingDuringLoadingDoesNotResurrectAnOlderSuccess() = runTest {
    val pending = CompletableDeferred<RecipeRequestResult>()
    val vm =
        model(
            RecipeRepository {
              if (++calls == 1) RecipeRequestResult.Success(input) else pending.await()
            })
    vm.requestRecipes()
    vm.selectOption(RecipePresentationOption.TITLE_ASCENDING)
    vm.requestRecipes()
    vm.selectOption(null)
    assertEquals(RecipesUiState.Loading, vm.uiState.value)
    pending.complete(RecipeRequestResult.Success(input.reversed()))
    runCurrent()
    assertEquals(listOf("a2", "a", "z"), ids(vm))
    assertEquals(2, calls)
  }

  @Test
  fun errorsAndEmptyListsRemainIntactWhenOptionsChange() = runTest {
    val vm = model()
    for (result in
        listOf(
            RecipeRequestResult.Success(emptyList()),
            RecipeRequestResult.Offline,
            RecipeRequestResult.ServiceError)) {
      response = result
      vm.requestRecipes()
      val state = vm.uiState.value
      vm.selectOption(RecipePresentationOption.TITLE_ASCENDING)
      assertEquals(state, vm.uiState.value)
      vm.selectOption(null)
      assertEquals(state, vm.uiState.value)
    }
    assertEquals(3, calls)
  }

  @Test
  fun chosenOrderSurvivesRetryAndClearingRestoresTheLatestResponse() = runTest {
    val vm = model()
    vm.requestRecipes()
    vm.selectOption(RecipePresentationOption.TITLE_DESCENDING)
    response = RecipeRequestResult.ServiceError
    vm.requestRecipes()
    assertEquals(RecipePresentationOption.TITLE_DESCENDING, vm.selectedOption.value)
    response = RecipeRequestResult.Success(input.reversed())
    vm.requestRecipes()
    assertEquals(listOf("z", "a2", "a"), ids(vm))
    vm.selectOption(null)
    assertEquals(listOf("a2", "a", "z"), ids(vm))
    assertEquals(3, calls)
  }
}
