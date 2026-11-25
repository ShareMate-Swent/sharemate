// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.item

import java.util.Locale

/**
 * The fixed categories an item can belong to, persisted as the exact enum name. An item without a
 * category stores `null`; [OTHER] is a category someone chose on purpose. Keep the names in sync
 * with `hasValidItemCategory` in `firestore.rules`.
 */
enum class ItemCategory {
  FRUITS,
  VEGETABLES,
  DAIRY,
  MEAT_FISH,
  DRINKS,
  PANTRY,
  FROZEN,
  OTHER;

  companion object {
    /** Spellings of categories typed before the list was fixed, lowercase, by category. */
    private val legacySpellings: Map<String, ItemCategory> =
        mapOf(
                FRUITS to listOf("fruit", "fruits", "fruta", "frutas"),
                VEGETABLES to
                    listOf("vegetable", "vegetables", "veggies", "légume", "légumes", "verdura"),
                DAIRY to listOf("dairy", "milk", "produits laitiers", "laitier", "lácteos"),
                MEAT_FISH to
                    listOf(
                        "meat",
                        "fish",
                        "meat & fish",
                        "meat and fish",
                        "viande",
                        "poisson",
                        "carne",
                        "pescado"),
                DRINKS to listOf("drink", "drinks", "beverages", "boisson", "boissons", "bebidas"),
                PANTRY to listOf("pantry", "épicerie", "despensa"),
                FROZEN to listOf("frozen", "surgelés", "congelados"),
                OTHER to listOf("other", "autre", "otro"))
            .flatMap { (category, spellings) -> spellings.map { it to category } }
            .toMap()

    /**
     * The category of a stored value. An enum name or a known older spelling, ignoring case, gives
     * its category; any other text gives [OTHER]; a missing or blank value gives `null`.
     */
    fun fromStored(value: String?): ItemCategory? {
      val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
      entries
          .firstOrNull { it.name.equals(text, ignoreCase = true) }
          ?.let {
            return it
          }
      return legacySpellings[text.lowercase(Locale.ROOT)] ?: OTHER
    }
  }
}
