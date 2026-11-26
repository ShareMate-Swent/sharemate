// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.recipes

import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemRepository
import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.HttpsCallableReference
import com.google.firebase.functions.HttpsCallableResult
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class FirebaseRecipeRepositoryTest {
  private fun row(image: Any? = "https://example.org/soup.jpg") =
      mapOf(
          "id" to "12",
          "title" to "Soup",
          "imageUrl" to image,
          "matchingIngredientCount" to 2.0,
          "missingIngredientCount" to 1L)

  private fun repository(response: Any?) =
      FirebaseRecipeRepository({ listOf("milk") }, { response })

  @Test
  fun missingNamesAreDecodedWithoutReorderingOrRecomputingMatches() = runTest {
    for (names in listOf(emptyList(), listOf("Salt"), listOf("Onion", "Salt", "Pepper"))) {
      val wire =
          row() + mapOf("missingIngredientCount" to names.size, "missingIngredients" to names)
      val result =
          repository(mapOf("recipes" to listOf(wire))).suggestRecipes()
              as RecipeRequestResult.Success
      assertEquals(names, result.recipes.single().missingIngredients)
      assertEquals(names.size, result.recipes.single().missingIngredientCount)
    }
  }

  @Test
  fun malformedMissingNameListsReturnServiceError() = runTest {
    for (names in
        listOf(
            null, "Salt", emptyList<String>(), listOf(12), listOf(""), listOf("Salt", "Onion"))) {
      assertEquals(
          RecipeRequestResult.ServiceError,
          repository(mapOf("recipes" to listOf(row() + ("missingIngredients" to names))))
              .suggestRecipes())
    }
  }

  @Test
  fun successEmptyAndMissingOptionalImage() = runTest {
    val success =
        repository(mapOf("recipes" to listOf(row()))).suggestRecipes()
            as RecipeRequestResult.Success
    assertEquals(
        listOf(RecipeSuggestion("12", "Soup", "https://example.org/soup.jpg", 2, 1)),
        success.recipes)
    assertEquals(
        RecipeRequestResult.Success(emptyList()),
        repository(mapOf("recipes" to emptyList<Any>())).suggestRecipes())
    for (r in listOf(row(null), row() - "imageUrl")) {
      assertNull(
          (repository(mapOf("recipes" to listOf(r))).suggestRecipes()
                  as RecipeRequestResult.Success)
              .recipes
              .single()
              .imageUrl)
    }
  }

  @Test
  fun rejectsMalformedResponsesWithoutThrowingToUi() = runTest {
    val invalidRows =
        listOf(
            null,
            row() - "title",
            row() + ("title" to " "),
            row() + ("matchingIngredientCount" to -1),
            row() + ("missingIngredientCount" to 1.5),
            row() + ("missingIngredientCount" to Double.NaN),
            row("file:///private.jpg"),
            row(12),
            row() + ("missingIngredientCount" to 2147483648L))
    for (r in invalidRows) assertEquals(
        RecipeRequestResult.ServiceError,
        repository(mapOf("recipes" to listOf(r))).suggestRecipes())
    for (response in
        listOf(
            null,
            emptyMap<String, Any>(),
            mapOf("recipes" to "bad"),
            mapOf("recipes" to listOf(row(), row())),
            mapOf("recipes" to List(11) { row() }))) {
      assertEquals(RecipeRequestResult.ServiceError, repository(response).suggestRecipes())
    }
  }

  private fun functionFailure(code: FirebaseFunctionsException.Code): FirebaseFunctionsException =
      mock(FirebaseFunctionsException::class.java).also { `when`(it.code).thenReturn(code) }

  @Test
  fun mapsNetworkServiceAndInventoryFailures() = runTest {
    for ((failure, expected) in
        listOf(
            IOException("offline") to RecipeRequestResult.Offline,
            functionFailure(FirebaseFunctionsException.Code.UNAVAILABLE) to
                RecipeRequestResult.Offline,
            functionFailure(FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED) to
                RecipeRequestResult.ServiceError,
            functionFailure(FirebaseFunctionsException.Code.INTERNAL) to
                RecipeRequestResult.ServiceError,
            IllegalStateException("bad response") to RecipeRequestResult.ServiceError)) {
      assertEquals(
          expected,
          FirebaseRecipeRepository({ listOf("milk") }, { throw failure }).suggestRecipes())
      assertEquals(
          expected,
          FirebaseRecipeRepository({ throw failure }, { error("Must not call") }).suggestRecipes())
    }
  }

  @Test
  fun emptyFridgeDoesNotCallFunctions() = runTest {
    assertEquals(
        RecipeRequestResult.Success(emptyList()),
        FirebaseRecipeRepository({ emptyList() }, { error("Must not call") }).suggestRecipes())
  }

  @Test
  fun cancellationPropagatesDuringInventoryAndServiceCalls() = runTest {
    for (duringInventory in listOf(true, false)) {
      val entered = CompletableDeferred<Unit>()
      var cancelled = false
      val pending: suspend () -> Nothing = {
        entered.complete(Unit)
        try {
          awaitCancellation()
        } finally {
          cancelled = true
        }
      }
      val repo =
          FirebaseRecipeRepository(
              { if (duringInventory) pending() else listOf("milk") }, { pending() })
      val job = launch {
        repo.suggestRecipes()
        fail("Cancellation must not become a result")
      }
      entered.await()
      job.cancelAndJoin()
      assertTrue(cancelled)
    }
  }

  @Test
  fun cancelledNonCooperativeResponseIsNeverPublished() = runTest {
    val entered = CompletableDeferred<Unit>()
    val response = CompletableDeferred<Any?>()
    var published = false
    val repo =
        FirebaseRecipeRepository(
            { listOf("milk") },
            {
              withContext(NonCancellable) {
                entered.complete(Unit)
                response.await()
              }
            })
    val job = launch {
      repo.suggestRecipes()
      published = true
    }
    entered.await()
    job.cancel()
    response.complete(mapOf("recipes" to listOf(row())))
    job.join()
    assertFalse(published)
  }

  @Test
  fun emptyFridgeDoesNotInitializeFirebaseFunctions() = runTest {
    val items = mock(ItemRepository::class.java)
    `when`(items.getFridgeItemsFromServer("home", "alice")).thenReturn(emptyList())
    val repository =
        FirebaseRecipeRepository(
            { error("An empty fridge must not initialize the service SDK") },
            RecipeIngredientProvider(items),
            "home",
            "alice")
    assertEquals(RecipeRequestResult.Success(emptyList()), repository.suggestRecipes())
  }

  @Test
  fun productionAdapterUsesProviderAndKeepsHouseholdAndUserBound() = runTest {
    val items = mock(ItemRepository::class.java)
    val functions = mock(FirebaseFunctions::class.java)
    val callable = mock(HttpsCallableReference::class.java)
    `when`(functions.getHttpsCallable("suggestRecipes")).thenReturn(callable)
    val result =
        HttpsCallableResult::class
            .java
            .getConstructor(Any::class.java)
            .newInstance(mapOf("recipes" to emptyList<Any>()))
    `when`(callable.call(any())).thenReturn(Tasks.forResult(result))
    for ((user, home) in listOf("alice" to "home", "alice" to "other", "bob" to "home")) {
      `when`(items.getFridgeItemsFromServer(home, user))
          .thenReturn(
              listOf(Item(id = "milk", name = " Milk ", ownerId = user, householdId = home)))
      assertEquals(
          RecipeRequestResult.Success(emptyList()),
          FirebaseRecipeRepository({ functions }, RecipeIngredientProvider(items), home, user)
              .suggestRecipes())
      verify(items).getFridgeItemsFromServer(home, user)
    }
    verify(callable, times(2)).call(mapOf("householdId" to "home", "ingredients" to listOf("milk")))
    verify(callable).call(mapOf("householdId" to "other", "ingredients" to listOf("milk")))
  }
}
