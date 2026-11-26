// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.recipes

import com.android.sharemate.model.item.ExpirationDates
import com.android.sharemate.model.item.ExpiryStatus
import com.android.sharemate.model.user.ExpirationPreferencesProvider
import java.time.LocalDate

/** Recipe-facing names for the three states calculated by [ExpirationDates.status]. */
enum class IngredientExpiryStatus {
  /** The expiry day itself or an earlier day. */
  EXPIRED,
  /** A future expiry within the user's inclusive "expires soon" window. */
  EXPIRES_SOON,
  /** Beyond that window, or with no known expiry date, matching the fridge. */
  NOT_EXPIRED_YET,
}

/**
 * One available stock entry and its freshness at the time of the request.
 *
 * @property ingredient Preserves the item ID, normalized name and expiry date.
 * @property status Classification using the fridge's shared boundary rule.
 */
data class IngredientWithExpiry(
    val ingredient: AvailableIngredient,
    val status: IngredientExpiryStatus,
)

/**
 * Annotates available ingredients without changing which items qualify for recipe requests.
 *
 * @param ingredients Supplies the single availability/normalization policy from #339.
 * @param preferences Reads the user's existing setting, with its documented default fallback.
 */
class RecipeIngredientExpiryProvider(
    private val ingredients: RecipeIngredientProvider,
    private val preferences: ExpirationPreferencesProvider,
) {
  /**
   * Reads a fresh inventory and, if nonempty, the user's threshold once for the whole batch.
   * [today] is one device-local calendar day for all entries; stored dates are read in UTC by
   * [ExpirationDates.status]. A new call refreshes both inventory and preferences. Read failures
   * from the inventory and coroutine cancellation propagate; settings failures use the provider's
   * fallback. No recipe service is invoked.
   */
  suspend fun ingredients(
      householdId: String,
      userId: String,
      today: LocalDate = LocalDate.now(),
  ): List<IngredientWithExpiry> {
    val available = ingredients.availableIngredients(householdId, userId)
    if (available.isEmpty()) return emptyList()
    val threshold = preferences.get(userId).soonDays.toLong()
    return available.map { ingredient ->
      val status =
          when (ExpirationDates.status(ingredient.expirationDate, today, threshold)) {
            ExpiryStatus.EXPIRED -> IngredientExpiryStatus.EXPIRED
            ExpiryStatus.SOON -> IngredientExpiryStatus.EXPIRES_SOON
            ExpiryStatus.NORMAL -> IngredientExpiryStatus.NOT_EXPIRED_YET
          }
      IngredientWithExpiry(ingredient, status)
    }
  }
}
