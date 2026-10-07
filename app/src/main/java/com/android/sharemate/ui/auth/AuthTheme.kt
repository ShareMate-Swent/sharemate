package com.android.sharemate.ui.auth

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The Figma entry flow stays light independently of the application's theme. */
@Composable
internal fun AuthTheme(content: @Composable () -> Unit) {
  MaterialTheme(
      colorScheme =
          lightColorScheme(
              primary = Color(0xFF6750A4),
              onPrimary = Color.White,
              background = Color(0xFFFEF7FF),
              onBackground = Color(0xFF1D1B20),
              surface = Color(0xFFFEF7FF),
              onSurface = Color(0xFF1D1B20),
              onSurfaceVariant = Color(0xFF49454F),
              outline = Color(0xFF79747E)),
      typography = MaterialTheme.typography,
      content = content)
}
