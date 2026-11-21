// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Raw colors of one mode of the app's design system, matching the `color/…` variables of the
 * redesign Figma file. Screens should not read these directly: use `MaterialTheme.colorScheme` or
 * [ShareMateTheme.statusColors] so a color change happens in one place.
 */
@Immutable
internal data class Palette(
    val background: Color,
    val surface: Color,
    val text: Color,
    val muted: Color,
    val border: Color,
    val divider: Color,
    val accent: Color,
    val onAccent: Color,
    val surfaceContainerLow: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val greenContainer: Color,
    val greenContent: Color,
    val amberContainer: Color,
    val amberContent: Color,
    val redContainer: Color,
    val redContent: Color,
    val blueContainer: Color,
    val blueContent: Color,
)

/** Light mode colors, as drawn in the redesign Figma file. */
internal val LightPalette =
    Palette(
        background = Color(0xFFF6F6F3),
        surface = Color(0xFFFFFFFF),
        text = Color(0xFF1C1F1D),
        muted = Color(0xFF5F6661),
        border = Color(0xFFE2E2DD),
        divider = Color(0xFFF0F0EC),
        accent = Color(0xFF1F5C45),
        onAccent = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFBFBF9),
        surfaceContainerHigh = Color(0xFFF0F0EC),
        surfaceContainerHighest = Color(0xFFE9E9E4),
        greenContainer = Color(0xFFE3F4E8),
        greenContent = Color(0xFF1F6B3A),
        amberContainer = Color(0xFFFDF0D5),
        amberContent = Color(0xFF7A4F00),
        redContainer = Color(0xFFFCE4E2),
        redContent = Color(0xFF9A2318),
        blueContainer = Color(0xFFE6EAF7),
        blueContent = Color(0xFF2F4A9A),
    )

/**
 * Dark mode colors: the same roles on dark green-grey surfaces, with a lighter accent and light
 * text on deep pastel containers so every pair stays readable.
 */
internal val DarkPalette =
    Palette(
        background = Color(0xFF121513),
        surface = Color(0xFF1B1F1C),
        text = Color(0xFFECEFEC),
        muted = Color(0xFFA3ABA5),
        border = Color(0xFF353B37),
        divider = Color(0xFF262B27),
        accent = Color(0xFF7BC4A2),
        onAccent = Color(0xFF0E2A1F),
        surfaceContainerLow = Color(0xFF171A18),
        surfaceContainerHigh = Color(0xFF252A26),
        surfaceContainerHighest = Color(0xFF2E342F),
        greenContainer = Color(0xFF1E3328),
        greenContent = Color(0xFF9FD9B4),
        amberContainer = Color(0xFF3A2E14),
        amberContent = Color(0xFFF2C877),
        redContainer = Color(0xFF3D1F1C),
        redContent = Color(0xFFF4A49B),
        blueContainer = Color(0xFF1F2840),
        blueContent = Color(0xFFAFC0F2),
    )

/** Material 3 color scheme built from a [palette], light or dark. */
internal fun colorSchemeOf(palette: Palette, dark: Boolean): ColorScheme {
  val base = if (dark) darkColorScheme() else lightColorScheme()
  return with(palette) {
    base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = greenContainer,
        onPrimaryContainer = greenContent,
        secondary = text,
        onSecondary = surface,
        secondaryContainer = blueContainer,
        onSecondaryContainer = blueContent,
        tertiary = amberContent,
        onTertiary = surface,
        tertiaryContainer = amberContainer,
        onTertiaryContainer = amberContent,
        error = redContent,
        onError = surface,
        errorContainer = redContainer,
        onErrorContainer = redContent,
        background = background,
        onBackground = text,
        surface = surface,
        onSurface = text,
        // A visible grey, distinct from the background: incoming chat bubbles and placeholder
        // tiles sit directly on the background and must not blend into it.
        surfaceVariant = surfaceContainerHighest,
        onSurfaceVariant = muted,
        surfaceTint = accent,
        outline = border,
        outlineVariant = divider,
        surfaceContainerLowest = if (dark) background else surface,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = if (dark) surface else background,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        inverseSurface = text,
        inverseOnSurface = background)
  }
}

/** Material 3 color scheme of the app in light mode. */
internal val LightColorScheme: ColorScheme = colorSchemeOf(LightPalette, dark = false)

/** Material 3 color scheme of the app in dark mode. */
internal val DarkColorScheme: ColorScheme = colorSchemeOf(DarkPalette, dark = true)

/**
 * A pair of colors for a pastel badge or tile: [container] fills the shape and [content] colors the
 * text or icon on it.
 */
@Immutable data class StatusColor(val container: Color, val content: Color)

/**
 * Colors that carry a meaning across features, so screens do not hardcode them: an item that
 * [expired], one [expiringSoon], one still [fresh], and the [shared] or [personal] ownership of an
 * item.
 */
@Immutable
data class StatusColors(
    val expired: StatusColor,
    val expiringSoon: StatusColor,
    val fresh: StatusColor,
    val shared: StatusColor,
    val personal: StatusColor,
)

/** Status colors built from a [palette], light or dark. */
internal fun statusColorsOf(palette: Palette): StatusColors =
    with(palette) {
      StatusColors(
          expired = StatusColor(redContainer, redContent),
          expiringSoon = StatusColor(amberContainer, amberContent),
          fresh = StatusColor(greenContainer, greenContent),
          shared = StatusColor(blueContainer, blueContent),
          personal = StatusColor(amberContainer, amberContent),
      )
    }

/** Status colors of the app in light mode. */
internal val LightStatusColors = statusColorsOf(LightPalette)

/** Status colors of the app in dark mode. */
internal val DarkStatusColors = statusColorsOf(DarkPalette)
