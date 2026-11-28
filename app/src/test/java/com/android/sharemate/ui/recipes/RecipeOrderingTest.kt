// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.recipes

import com.android.sharemate.model.recipes.RecipeSuggestion
import org.junit.Assert.*
import org.junit.Test

class RecipeOrderingTest {
  private fun recipe(id: String, missing: Int, matching: Int = 1) =
      RecipeSuggestion(id, id, null, matching, missing)

  @Test
  fun zeroOneManyAndEqualCountsPreserveRepositoryOrderWithoutMutatingInput() {
    val input =
        listOf(
            recipe("many-a", 3),
            recipe("full-a", 0, 1),
            recipe("one-a", 1, 1),
            recipe("many-b", 3),
            recipe("full-b", 0, 10),
            recipe("one-b", 1, 20),
            recipe("two", 2))
    val before = input.toList()
    assertEquals(
        listOf("full-a", "full-b", "one-a", "one-b", "two", "many-a", "many-b"),
        fullMatchesFirst(input).map { it.id })
    assertEquals(before, input)
  }

  @Test
  fun emptySingletonAndAlreadyOrderedInputsRetainTheirOrder() {
    assertTrue(fullMatchesFirst(emptyList()).isEmpty())
    for (missing in listOf(0, 1, 8)) {
      val single = listOf(recipe("single", missing))
      assertEquals(single, fullMatchesFirst(single))
    }
    val ordered = listOf(recipe("full", 0), recipe("one", 1), recipe("many", 8))
    assertEquals(ordered, fullMatchesFirst(ordered))
  }
}
