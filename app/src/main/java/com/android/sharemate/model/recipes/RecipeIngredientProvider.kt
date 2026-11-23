// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.recipes

import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.item.ItemStatus
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.flow.first

/** Builds recipe request ingredients from a fresh, user-visible fridge snapshot. */
class RecipeIngredientProvider(private val items: ItemRepository) {
  /**
   * Uses active shared items and the user's own personal items. Expiration does not remove an
   * active item, matching the fridge; consumed/discarded items and zero quantities are excluded.
   * Read failures and cancellation propagate so they cannot be mistaken for an empty fridge.
   */
  suspend fun ingredients(householdId: String, userId: String): List<String> {
    if (householdId.isBlank() || userId.isBlank()) return emptyList()
    return items.getFridgeItems(householdId, userId).first().asIngredients(householdId, userId)
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

private fun List<Item>.asIngredients(householdId: String, userId: String): List<String> =
    asSequence()
        .filter {
          it.householdId == householdId &&
              (it.isShared || it.ownerId == userId) &&
              it.status == ItemStatus.ACTIVE &&
              it.quantity > 0
        }
        .map {
          Normalizer.normalize(it.name, Normalizer.Form.NFKC)
              .replace(ingredientWhitespace, " ")
              .trim()
              .lowercase(Locale.ROOT)
        }
        .filter(String::isNotEmpty)
        .distinct()
        .sorted()
        .toList()

private val ingredientWhitespace = Regex("[\\p{Z}\\s]+")
