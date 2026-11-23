// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.recipes

import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.item.ItemStatus
import java.text.Normalizer
import java.util.Date
import java.util.Locale

/**
 * One usable stock entry, before recipe request names are deduplicated.
 *
 * @property itemId Original fridge item identifier; equal names can belong to different items.
 * @property name Recipe request name, normalized exactly as in
 *   [RecipeIngredientProvider.ingredients].
 * @property expirationDate Copy of the stored UTC expiry day, or null when unknown.
 */
data class AvailableIngredient(val itemId: String, val name: String, val expirationDate: Date?)

/** Builds recipe request ingredients from a server-refreshed, user-visible fridge snapshot. */
class RecipeIngredientProvider(private val items: ItemRepository) {
  /**
   * Uses active shared items and the user's own personal items. Expiration does not remove an
   * active item, matching the fridge; consumed/discarded items and zero quantities are excluded.
   * Requires a server read; cached inventory cannot complete the request. Offline/read failures and
   * cancellation propagate so they cannot be mistaken for an empty fridge.
   */
  suspend fun ingredients(householdId: String, userId: String): List<String> {
    return availableIngredients(householdId, userId).map { it.name }.distinct().sorted()
  }

  /**
   * Same availability and name rules as [ingredients], retaining each item's identity and expiry.
   * Results follow inventory order; two stocks with the same name stay separate. Returned dates do
   * not alias the inventory's mutable [Date] values.
   */
  suspend fun availableIngredients(householdId: String, userId: String): List<AvailableIngredient> {
    if (householdId.isBlank() || userId.isBlank()) return emptyList()
    return items
        .getFridgeItemsFromServer(householdId, userId)
        .asAvailableIngredients(householdId, userId)
  }

  /**
   * Calls the recipe service only when usable ingredients exist. The service adapter supplies
   * [request] (and owns transport encoding and error mapping); no service or UI model is assumed.
   */
  suspend fun <T> suggest(
      householdId: String,
      userId: String,
      request: suspend (List<String>) -> List<T>,
  ): List<T> {
    val names = ingredients(householdId, userId)
    return if (names.isEmpty()) emptyList() else request(names)
  }
}

private fun List<Item>.asAvailableIngredients(
    householdId: String,
    userId: String,
): List<AvailableIngredient> =
    asSequence()
        .filter {
          it.householdId == householdId &&
              (it.isShared || it.ownerId == userId) &&
              it.status == ItemStatus.ACTIVE &&
              it.quantity > 0
        }
        .mapNotNull { item ->
          val name =
              Normalizer.normalize(item.name, Normalizer.Form.NFKC)
                  .replace(ingredientWhitespace, " ")
                  .trim()
                  .lowercase(Locale.ROOT)
          if (name.isEmpty()) null
          else AvailableIngredient(item.id, name, item.expirationDate?.let { Date(it.time) })
        }
        .toList()

private val ingredientWhitespace = Regex("[\\p{Z}\\s]+")
