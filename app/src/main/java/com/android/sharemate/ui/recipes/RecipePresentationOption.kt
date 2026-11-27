// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.recipes

import androidx.annotation.StringRes
import com.android.sharemate.R

/**
 * Central registry of list-only options, including labels and behavior. Add entries here without
 * changing RecipesScreen or RecipesContent. Null selection preserves the default list order. Title
 * ordering is case-insensitive and stable for ties; it does not filter or refetch recipes.
 */
enum class RecipePresentationOption(
    val id: String,
    @StringRes val label: Int,
    private val comparator: Comparator<RecipeCardData>,
) {
  TITLE_ASCENDING(
      "title_ascending",
      R.string.recipes_sort_title_ascending,
      compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }),
  TITLE_DESCENDING(
      "title_descending",
      R.string.recipes_sort_title_descending,
      compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title });

  /** Returns a sorted copy, preserving input order for titles that compare equally. */
  fun apply(recipes: List<RecipeCardData>): List<RecipeCardData> = recipes.sortedWith(comparator)
}
