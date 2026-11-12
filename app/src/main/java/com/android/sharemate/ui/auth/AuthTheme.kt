// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val AuthBackground = Color(0xFFFEF7FF)
private val AuthForeground = Color(0xFF1D1B20)

private val AuthColorScheme =
    lightColorScheme(
        primary = Color(0xFF6750A4),
        onPrimary = Color.White,
        background = AuthBackground,
        onBackground = AuthForeground,
        surface = AuthBackground,
        onSurface = AuthForeground,
        onSurfaceVariant = Color(0xFF49454F),
        outline = Color(0xFF79747E))

/** The Figma entry flow stays light independently of the application's theme. */
@Composable
internal fun AuthTheme(updateSystemBars: Boolean = false, content: @Composable () -> Unit) {
  val view = LocalView.current
  if (updateSystemBars && !view.isInEditMode) {
    val window = view.context.findActivity()?.window
    if (window != null) {
      val controller = WindowCompat.getInsetsController(window, view)
      DisposableEffect(window, view) {
        val oldStatusColor = window.statusBarColor
        val oldNavigationColor = window.navigationBarColor
        val oldStatusIcons = controller.isAppearanceLightStatusBars
        val oldNavigationIcons = controller.isAppearanceLightNavigationBars
        onDispose {
          window.statusBarColor = oldStatusColor
          window.navigationBarColor = oldNavigationColor
          controller.isAppearanceLightStatusBars = oldStatusIcons
          controller.isAppearanceLightNavigationBars = oldNavigationIcons
        }
      }
      SideEffect {
        window.statusBarColor = AuthColorScheme.background.toArgb()
        window.navigationBarColor = AuthColorScheme.background.toArgb()
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true
      }
    }
  }
  MaterialTheme(
      colorScheme = AuthColorScheme, typography = MaterialTheme.typography, content = content)
}

private fun Context.findActivity(): Activity? =
    when (this) {
      is Activity -> this
      is ContextWrapper -> baseContext.findActivity()
      else -> null
    }
