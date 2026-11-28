// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.recipes

import com.android.sharemate.model.recipes.RecipeSuggestion

/**
 * Full matches first, followed by ascending missing count. The stable sort preserves repository
 * order for every tie, including complete matches; matched count and title are not tie-breakers.
 * Returns a new list without mutating the repository response. Applied once per successful request.
 */
internal fun fullMatchesFirst(recipes: List<RecipeSuggestion>): List<RecipeSuggestion> =
    recipes.sortedBy { it.missingIngredientCount }
