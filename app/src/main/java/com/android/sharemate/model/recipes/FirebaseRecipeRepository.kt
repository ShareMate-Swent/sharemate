// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.recipes

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import java.io.IOException
import java.net.URI
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await

/** One immutable user/household context; provider credentials never enter the Android client. */
class FirebaseRecipeRepository
internal constructor(
    private val ingredients: suspend () -> List<String>,
    private val request: suspend (List<String>) -> Any?,
) : RecipeRepository {
  constructor(
      functions: () -> FirebaseFunctions,
      provider: RecipeIngredientProvider,
      householdId: String,
      userId: String,
  ) : this(
      { provider.ingredients(householdId, userId) },
      { names ->
        functions()
            .getHttpsCallable("suggestRecipes")
            .call(mapOf("householdId" to householdId, "ingredients" to names))
            .await()
            .data
      })

  override suspend fun suggestRecipes(): RecipeRequestResult =
      try {
        val names = ingredients()
        coroutineContext.ensureActive()
        if (names.isEmpty()) RecipeRequestResult.Success(emptyList())
        else {
          val response = request(names)
          coroutineContext.ensureActive()
          RecipeRequestResult.Success(decode(response))
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        coroutineContext.ensureActive()
        val offline =
            when (failure) {
              is IOException,
              is FirebaseNetworkException -> true
              is FirebaseFunctionsException ->
                  failure.code == FirebaseFunctionsException.Code.UNAVAILABLE
              is FirebaseFirestoreException ->
                  failure.code == FirebaseFirestoreException.Code.UNAVAILABLE
              else -> false
            }
        if (offline) RecipeRequestResult.Offline else RecipeRequestResult.ServiceError
      }

  private fun decode(response: Any?): List<RecipeSuggestion> {
    val rows =
        (response as? Map<*, *>)?.get("recipes") as? List<*> ?: error("Invalid recipe response")
    require(rows.size <= 10)
    val recipes =
        rows.map { value ->
          val row = value as? Map<*, *> ?: error("Invalid recipe")
          val id = row["id"] as? String ?: error("Missing recipe ID")
          val title = row["title"] as? String ?: error("Missing title")
          require(id.isNotBlank() && id.length <= 128 && title.isNotBlank() && title.length <= 200)
          val image =
              row["imageUrl"]?.let {
                require(it is String && it.length <= 2048)
                require(URI(it).scheme == "https" && !URI(it).host.isNullOrBlank())
                it
              }
          val missingCount = count(row["missingIngredientCount"])
          val missingNames =
              if (!row.containsKey("missingIngredients")) emptyList()
              else {
                val names =
                    row["missingIngredients"] as? List<*> ?: error("Invalid missing ingredients")
                require(names.size == missingCount)
                names.map {
                  require(it is String && it.isNotBlank() && it.length <= 200)
                  it
                }
              }
          RecipeSuggestion(
              id, title, image, count(row["matchingIngredientCount"]), missingCount, missingNames)
        }
    require(recipes.map { it.id }.distinct().size == recipes.size)
    return recipes
  }

  private fun count(value: Any?): Int {
    val number = (value as? Number)?.toDouble() ?: error("Missing ingredient count")
    require(
        number.isFinite() &&
            number in 0.0..Int.MAX_VALUE.toDouble() &&
            number == number.toInt().toDouble())
    return number.toInt()
  }

  companion object {
    const val REGION = "europe-west6"
  }
}
