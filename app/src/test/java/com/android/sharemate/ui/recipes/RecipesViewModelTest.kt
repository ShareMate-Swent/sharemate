// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.recipes

import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.recipes.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecipesViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val store = ViewModelStore()
  private val soup = RecipeSuggestion("soup", "Soup", null, 2, 1)

  @Before fun setUp() = Dispatchers.setMain(dispatcher)

  @After
  fun tearDown() {
    store.clear()
    Dispatchers.resetMain()
  }

  private fun model(repository: RecipeRepository) =
      RecipesViewModel(repository).also { store.put("recipes", it) }

  @Test
  fun noRequestUntilExplicitActionAndLoadingUntilCompletion() = runTest {
    var calls = 0
    val pending = CompletableDeferred<RecipeRequestResult>()
    val vm =
        model(
            RecipeRepository {
              calls++
              pending.await()
            })
    runCurrent()
    assertEquals(RecipesUiState.Idle, vm.uiState.value)
    assertEquals(0, calls)
    vm.requestRecipes()
    assertEquals(1, calls)
    assertEquals(RecipesUiState.Loading, vm.uiState.value)
    pending.complete(RecipeRequestResult.Success(listOf(soup)))
    runCurrent()
    assertEquals(
        RecipesUiState.Success(listOf(RecipeCardData("soup", "Soup", null, 2, 1))),
        vm.uiState.value)
  }

  @Test
  fun emptyResponseIsSuccessfulAndDoesNotRetryItself() = runTest {
    var calls = 0
    val vm =
        model(
            RecipeRepository {
              calls++
              RecipeRequestResult.Success(emptyList())
            })
    vm.requestRecipes()
    advanceUntilIdle()
    assertEquals(RecipesUiState.Success(emptyList()), vm.uiState.value)
    assertEquals(1, calls)
  }

  @Test
  fun errorsRemainTypedAndExplicitRetryCanRecover() = runTest {
    var response: RecipeRequestResult = RecipeRequestResult.Offline
    var calls = 0
    val vm =
        model(
            RecipeRepository {
              calls++
              response
            })
    vm.requestRecipes()
    assertEquals(RecipesUiState.Error(RecipesErrorReason.OFFLINE), vm.uiState.value)
    response = RecipeRequestResult.ServiceError
    vm.requestRecipes()
    assertEquals(RecipesUiState.Error(RecipesErrorReason.SERVICE), vm.uiState.value)
    response = RecipeRequestResult.Success(listOf(soup))
    vm.requestRecipes()
    assertTrue(vm.uiState.value is RecipesUiState.Success)
    assertEquals(3, calls)
  }

  @Test
  fun unexpectedRepositoryFailureIsNotThrownToTheScreen() = runTest {
    val vm = model(RecipeRepository { throw IllegalStateException("Broken repository") })
    vm.requestRecipes()
    assertEquals(RecipesUiState.Error(RecipesErrorReason.SERVICE), vm.uiState.value)
  }

  @Test
  fun clearingOwnerCancelsRequestWithoutDisplayingServiceFailure() = runTest {
    var cancelled = false
    val vm =
        model(
            RecipeRepository {
              try {
                awaitCancellation()
              } finally {
                cancelled = true
              }
            })
    vm.requestRecipes()
    store.clear()
    runCurrent()
    assertTrue(cancelled)
    assertEquals(RecipesUiState.Loading, vm.uiState.value)
  }

  @Test
  fun repositoryCancellationIsNotConvertedIntoAnError() = runTest {
    val vm = model(RecipeRepository { throw CancellationException("Cancelled") })
    vm.requestRecipes()
    runCurrent()
    assertEquals(RecipesUiState.Loading, vm.uiState.value)
  }

  @Test
  fun eachPressCallsRepositoryOnceAndObsoleteNonCooperativeResponseCannotWin() = runTest {
    var calls = 0
    val oldResponse = CompletableDeferred<RecipeRequestResult>()
    val latestResponse = CompletableDeferred<RecipeRequestResult>()
    val vm =
        model(
            RecipeRepository {
              calls++
              if (calls == 1) withContext(NonCancellable) { oldResponse.await() }
              else latestResponse.await()
            })
    vm.requestRecipes()
    vm.requestRecipes()
    assertEquals(2, calls)
    latestResponse.complete(RecipeRequestResult.Success(listOf(soup)))
    runCurrent()
    val latest = vm.uiState.value
    oldResponse.complete(RecipeRequestResult.ServiceError)
    runCurrent()
    assertEquals(latest, vm.uiState.value)
    assertTrue(latest is RecipesUiState.Success)
  }
}
