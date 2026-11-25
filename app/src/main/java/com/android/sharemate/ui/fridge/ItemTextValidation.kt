// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.fridge

import com.android.sharemate.model.text.TextLimits
import com.android.sharemate.model.text.exceeds

/**
 * The text-length error of an item form, or null when the name fits. Typing is already cut to the
 * limit, so this only fires for a value that was not typed, such as an item saved before the limit
 * existed and opened for editing; it keeps that case from reaching the server and being refused
 * there. The category is picked from a fixed list, so it has no length to check.
 */
internal fun validateItemTexts(name: String): FridgeFormError? =
    if (name.exceeds(TextLimits.ITEM_NAME)) FridgeFormError.NAME_TOO_LONG else null
