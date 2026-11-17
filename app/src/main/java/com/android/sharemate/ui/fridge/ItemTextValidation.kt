// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.fridge

import com.android.sharemate.model.text.TextLimits
import com.android.sharemate.model.text.exceeds

/**
 * The first text-length error of an item form, or null when the texts fit. Typing is already cut to
 * the limits, so this only fires for a value that was not typed, such as an item saved before the
 * limits existed and opened for editing; it keeps that case from reaching the server and being
 * refused there.
 */
internal fun validateItemTexts(name: String, category: String): FridgeFormError? =
    when {
      name.exceeds(TextLimits.ITEM_NAME) -> FridgeFormError.NAME_TOO_LONG
      category.exceeds(TextLimits.ITEM_CATEGORY) -> FridgeFormError.CATEGORY_TOO_LONG
      else -> null
    }
