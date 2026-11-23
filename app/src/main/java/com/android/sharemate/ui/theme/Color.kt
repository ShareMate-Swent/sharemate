// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Raw colors of one mode of the app's design system, using the roles of the redesign Figma file.
 * Screens should not read these directly: use `MaterialTheme.colorScheme` or
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
    val accentContainer: Color,
    val onAccentContainer: Color,
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

/**
 * Light mode colors: the redesign's roles with the app's purple accent and purple-grey neutrals.
 */
internal val LightPalette =
    Palette(
        background = Color(0xFFF7F3F9),
        surface = Color(0xFFFFFFFF),
        text = Color(0xFF1D1B20),
        muted = Color(0xFF625B6B),
        border = Color(0xFFE3DEE7),
        divider = Color(0xFFF0ECF3),
        accent = Color(0xFF6750A4),
        onAccent = Color(0xFFFFFFFF),
        accentContainer = Color(0xFFEADDFF),
        onAccentContainer = Color(0xFF4F378B),
        surfaceContainerLow = Color(0xFFFBF9FC),
        surfaceContainerHigh = Color(0xFFF0ECF3),
        surfaceContainerHighest = Color(0xFFE9E4EC),
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
 * Dark mode colors: the same roles on dark purple-grey surfaces, with a lighter accent and light
 * text on deep pastel containers so every pair stays readable.
 */
internal val DarkPalette =
    Palette(
        background = Color(0xFF141218),
        surface = Color(0xFF1D1B20),
        text = Color(0xFFE6E0E9),
        muted = Color(0xFFCAC4D0),
        border = Color(0xFF3B3740),
        divider = Color(0xFF2B2930),
        accent = Color(0xFFD0BCFF),
        onAccent = Color(0xFF381E72),
        accentContainer = Color(0xFF4F378B),
        onAccentContainer = Color(0xFFEADDFF),
        surfaceContainerLow = Color(0xFF19171D),
        surfaceContainerHigh = Color(0xFF2B2930),
        surfaceContainerHighest = Color(0xFF36343B),
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
        primaryContainer = accentContainer,
        onPrimaryContainer = onAccentContainer,
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

/** Brand colors used by the ShareMate launch reveal and its entry actions. */
internal object ShareMateBrandColors {
  val darkName = Color(0xFF493052)
  val mintName = Color(0xFF9168BB)
  val progressTrack = Color(0xFFEEE8F4)
  val progressLabel = Color(0xFF786780)
}

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
