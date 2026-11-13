// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.text

/**
 * Longest user-entered texts, counted in Unicode code points. They are the single source of truth
 * for the forms, the repositories and the counters; `firestore.rules` repeats them because rules
 * cannot import Kotlin and must therefore be changed together with this object.
 */
object TextLimits {
  /** Longest household (fridge) name. */
  const val HOUSEHOLD_NAME = 40

  /** Longest item name, also applied to the rows of a scanned receipt. */
  const val ITEM_NAME = 80

  /** Longest optional item category. */
  const val ITEM_CATEGORY = 40

  /** Longest new password; firebase itself accepts up to 4096 characters. */
  const val PASSWORD = 128

  /** Longest email address, the limit of RFC 5321. */
  const val EMAIL = 254
}

/** Number of Unicode code points, the unit of [TextLimits] (an emoji counts as one, not two). */
fun String.codePointLength(): Int = codePointCount(0, length)

/** True when this text has more than [max] Unicode code points. */
fun String.exceeds(max: Int): Boolean = codePointLength() > max

/** This text cut to at most [max] code points, never splitting a surrogate pair. */
fun String.limitedTo(max: Int): String =
    if (exceeds(max)) substring(0, offsetByCodePoints(0, max)) else this
