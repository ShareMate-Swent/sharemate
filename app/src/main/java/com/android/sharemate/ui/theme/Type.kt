// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.android.sharemate.R

/** Weights of Plus Jakarta Sans that the design system uses. */
internal val PlusJakartaSansWeights =
    listOf(
        FontWeight.Normal,
        FontWeight.Medium,
        FontWeight.SemiBold,
        FontWeight.Bold,
        FontWeight.ExtraBold)

/**
 * Plus Jakarta Sans, the app's typeface. It ships as one variable font (SIL Open Font License, from
 * google/fonts), so each weight is the same file with a different `wght` axis value.
 */
@OptIn(ExperimentalTextApi::class)
val PlusJakartaSans: FontFamily =
    FontFamily(
        PlusJakartaSansWeights.map { weight ->
          Font(
              resId = R.font.plus_jakarta_sans,
              weight = weight,
              variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
        })

private fun style(
    size: Int,
    weight: FontWeight,
    lineHeight: Int,
    letterSpacing: Double = 0.0,
): TextStyle =
    TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = letterSpacing.sp)

/**
 * Material 3 typography of the app, mapped from the text styles of the redesign Figma file:
 * `Number/XL` is [Typography.displayLarge], `Display` is [Typography.displayMedium], `Title/L` is
 * [Typography.displaySmall], `Title/M` is [Typography.headlineMedium], `Heading` is
 * [Typography.titleMedium], `Body` is [Typography.bodyLarge], `Caption` is [Typography.bodySmall],
 * `Button` is [Typography.labelLarge] and `Label` is [Typography.labelMedium].
 */
val Typography =
    Typography(
        displayLarge = style(60, FontWeight.ExtraBold, 64, letterSpacing = -2.0),
        displayMedium = style(38, FontWeight.ExtraBold, 42, letterSpacing = -1.0),
        displaySmall = style(34, FontWeight.ExtraBold, 38, letterSpacing = -0.8),
        headlineLarge = style(30, FontWeight.ExtraBold, 36, letterSpacing = -0.6),
        headlineMedium = style(26, FontWeight.ExtraBold, 32, letterSpacing = -0.5),
        headlineSmall = style(22, FontWeight.ExtraBold, 28, letterSpacing = -0.3),
        titleLarge = style(20, FontWeight.Bold, 26),
        titleMedium = style(15, FontWeight.Bold, 20),
        titleSmall = style(14, FontWeight.SemiBold, 20),
        bodyLarge = style(15, FontWeight.Normal, 22),
        bodyMedium = style(14, FontWeight.Normal, 20),
        bodySmall = style(13, FontWeight.Normal, 18),
        labelLarge = style(16, FontWeight.Bold, 20),
        labelMedium = style(13, FontWeight.SemiBold, 16),
        labelSmall = style(12, FontWeight.SemiBold, 16),
    )
