// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

/**
 * Material 3 shapes of the app, taken from the `radius/…` variables of the redesign Figma file:
 * chips and menus are small, buttons and fields medium, cards large and sheets or dialogs extra
 * large.
 */
val Shapes =
    Shapes(
        extraSmall = RoundedCornerShape(Dimens.cornerRadiusXs),
        small = RoundedCornerShape(Dimens.cornerRadiusS),
        medium = RoundedCornerShape(Dimens.cornerRadiusM),
        large = RoundedCornerShape(Dimens.cornerRadiusL),
        extraLarge = RoundedCornerShape(Dimens.cornerRadiusXl),
    )
