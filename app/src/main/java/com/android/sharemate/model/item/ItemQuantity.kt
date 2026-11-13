// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.item

/**
 * The quantity of an item. Every form that accepts one reads it through [parse], so they all apply
 * the same boundary rule. `firestore.rules` repeats [MAX] because rules cannot import Kotlin.
 */
object ItemQuantity {
  /** Smallest quantity of an item. */
  const val MIN = 1

  /** Largest quantity of an item. */
  const val MAX = 9_999

  /** Longest quantity text kept in a field: a longer text can never be a valid quantity. */
  const val MAX_INPUT_LENGTH = 6

  /** Every valid quantity. */
  val RANGE = MIN..MAX

  /**
   * The whole number typed in [input], ignoring surrounding spaces, or null when not in [RANGE].
   */
  fun parse(input: String): Int? = input.trim().toIntOrNull()?.takeIf { it in RANGE }
}
