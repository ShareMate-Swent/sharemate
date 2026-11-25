// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Provides the [StatusColors] of the current theme. */
internal val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/**
 * The app's theme: the design system's colors, Plus Jakarta Sans typography and rounded shapes,
 * plus the [StatusColors] read through [ShareMateTheme.statusColors]. It follows the phone's dark
 * mode unless [darkTheme] says otherwise, and paints the status and navigation bars with the
 * background color and icons that contrast with it.
 */
@Composable
fun ShareMateTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
  val statusColors = if (darkTheme) DarkStatusColors else LightStatusColors
  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as? Activity)?.window ?: return@SideEffect
      // The launch theme leaves the navigation bar white, so both bars are painted here.
      @Suppress("DEPRECATION")
      window.statusBarColor = colorScheme.background.toArgb()
      @Suppress("DEPRECATION")
      window.navigationBarColor = colorScheme.background.toArgb()
      WindowCompat.getInsetsController(window, view).apply {
        isAppearanceLightStatusBars = !darkTheme
        isAppearanceLightNavigationBars = !darkTheme
      }
    }
  }
  CompositionLocalProvider(LocalStatusColors provides statusColors) {
    MaterialTheme(
        colorScheme = colorScheme, typography = Typography, shapes = Shapes, content = content)
  }
}

/** Values of the app's theme that `MaterialTheme` does not cover. */
object ShareMateTheme {
  /** Colors for expiry and ownership badges, from the closest [ShareMateTheme]. */
  val statusColors: StatusColors
    @Composable @ReadOnlyComposable get() = LocalStatusColors.current
}
