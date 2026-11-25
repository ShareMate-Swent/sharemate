// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.ResourceFont
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class ShareMateThemeTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private class Captured(
      val colors: ColorScheme,
      val typography: Typography,
      val shapes: Shapes,
      val status: StatusColors,
  )

  private fun captureTheme(
      theme: @Composable (@Composable () -> Unit) -> Unit = { ShareMateTheme(content = it) }
  ): Captured {
    lateinit var captured: Captured
    compose.setContent {
      theme {
        captured =
            Captured(
                MaterialTheme.colorScheme,
                MaterialTheme.typography,
                MaterialTheme.shapes,
                ShareMateTheme.statusColors)
      }
    }
    compose.waitForIdle()
    return captured
  }

  private fun lightStatusBarIcons(): Boolean {
    val window = compose.activity.window
    return WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars
  }

  @Suppress("DEPRECATION")
  private fun statusBarColor(): Int = compose.activity.window.statusBarColor

  @Test
  fun lightPhoneGetsTheLightDesignSystem() {
    val theme = captureTheme()

    assertEquals(LightPalette.accent, theme.colors.primary)
    assertEquals(LightPalette.background, theme.colors.background)
    assertEquals(LightPalette.text, theme.colors.onBackground)
    assertEquals(LightPalette.muted, theme.colors.onSurfaceVariant)
    assertEquals(LightStatusColors, theme.status)
    assertEquals(Shapes, theme.shapes)
  }

  @Test
  @Config(qualifiers = "night")
  fun darkPhoneGetsTheDarkDesignSystem() {
    val theme = captureTheme()

    assertEquals(DarkPalette.accent, theme.colors.primary)
    assertEquals(DarkPalette.background, theme.colors.background)
    assertEquals(DarkPalette.text, theme.colors.onBackground)
    assertEquals(DarkStatusColors, theme.status)
    assertEquals(Shapes, theme.shapes)
  }

  @Test
  fun darkThemeArgumentOverridesThePhoneSetting() {
    val theme = captureTheme { ShareMateTheme(darkTheme = true, content = it) }

    assertEquals(DarkPalette.background, theme.colors.background)
    assertEquals(DarkStatusColors, theme.status)
  }

  @Test
  fun lightStatusBarUsesTheBackgroundWithDarkIcons() {
    captureTheme()

    assertEquals(LightPalette.background.toArgb(), statusBarColor())
    assertTrue(lightStatusBarIcons())
  }

  @Test
  @Config(qualifiers = "night")
  fun darkStatusBarUsesTheBackgroundWithLightIcons() {
    captureTheme()

    assertEquals(DarkPalette.background.toArgb(), statusBarColor())
    assertFalse(lightStatusBarIcons())
  }

  private fun lightNavigationBarIcons(): Boolean {
    val window = compose.activity.window
    return WindowCompat.getInsetsController(window, window.decorView)
        .isAppearanceLightNavigationBars
  }

  @Suppress("DEPRECATION")
  private fun navigationBarColor(): Int = compose.activity.window.navigationBarColor

  @Test
  fun lightNavigationBarUsesTheBackgroundWithDarkIcons() {
    captureTheme()

    assertEquals(LightPalette.background.toArgb(), navigationBarColor())
    assertTrue(lightNavigationBarIcons())
  }

  @Test
  @Config(qualifiers = "night")
  fun darkNavigationBarUsesTheBackgroundWithLightIcons() {
    compose.activity.window.apply {
      // Start from the launch theme's white bar, the state the bug left on screen.
      @Suppress("DEPRECATION")
      navigationBarColor = android.graphics.Color.WHITE
      WindowCompat.getInsetsController(this, decorView).isAppearanceLightNavigationBars = true
    }

    captureTheme()

    assertEquals(DarkPalette.background.toArgb(), navigationBarColor())
    assertFalse(lightNavigationBarIcons())
  }

  @Test
  fun everyTextStyleUsesPlusJakartaSans() {
    val styles =
        with(captureTheme().typography) {
          listOf(
              displayLarge,
              displayMedium,
              displaySmall,
              headlineLarge,
              headlineMedium,
              headlineSmall,
              titleLarge,
              titleMedium,
              titleSmall,
              bodyLarge,
              bodyMedium,
              bodySmall,
              labelLarge,
              labelMedium,
              labelSmall)
        }

    styles.forEach { assertEquals(PlusJakartaSans, it.fontFamily) }
    styles.forEach { assertTrue(it.fontWeight in PlusJakartaSansWeights) }
  }

  @Test
  fun eachFontWeightSetsTheMatchingWeightAxisOfTheVariableFont() {
    val fonts = (PlusJakartaSans as FontListFontFamily).fonts.filterIsInstance<ResourceFont>()

    PlusJakartaSansWeights.forEach { weight: FontWeight ->
      val font = fonts.single { it.weight == weight }
      assertEquals(R.font.plus_jakarta_sans, font.resId)
      val axis = font.variationSettings.settings.single { it.axisName == "wght" }
      assertEquals(weight.weight.toFloat(), axis.toVariationValue(null), 0f)
    }
  }

  @Test
  fun lightTextColorsAreReadableOnTheirBackgrounds() {
    assertReadable(LightColorScheme, LightStatusColors)
  }

  @Test
  fun darkTextColorsAreReadableOnTheirBackgrounds() {
    assertReadable(DarkColorScheme, DarkStatusColors)
  }

  @Test
  fun lightSurfaceVariantStandsOutFromTheBackground() {
    assertStandsOut(LightColorScheme)
  }

  @Test
  fun darkSurfaceVariantStandsOutFromTheBackground() {
    assertStandsOut(DarkColorScheme)
  }

  /** Incoming chat bubbles use surfaceVariant on the background; they must not blend into it. */
  private fun assertStandsOut(colors: ColorScheme) {
    val minimum = 1.1
    val ratio = contrast(colors.surfaceVariant, colors.background)
    assertTrue("surfaceVariant only reaches $ratio against the background", ratio >= minimum)
  }

  @Test
  fun primaryIsPurpleWhileFreshStatusStaysGreen() {
    assertPrimaryIsNotStatusGreen(LightColorScheme, LightPalette, LightStatusColors)
    assertPrimaryIsNotStatusGreen(DarkColorScheme, DarkPalette, DarkStatusColors)
  }

  private fun assertPrimaryIsNotStatusGreen(
      colors: ColorScheme,
      palette: Palette,
      status: StatusColors
  ) {
    assertEquals(palette.accent, colors.primary)
    assertEquals(palette.accentContainer, colors.primaryContainer)
    assertEquals(palette.greenContainer, status.fresh.container)
    assertEquals(palette.greenContent, status.fresh.content)
    assertNotEquals(status.fresh.content, colors.primary)
    assertNotEquals(status.fresh.container, colors.primaryContainer)
  }

  private fun assertReadable(colors: ColorScheme, status: StatusColors) {
    val minimum = 4.5
    val pairs =
        listOf(
            colors.onBackground to colors.background,
            colors.onSurface to colors.surface,
            colors.onSurfaceVariant to colors.surface,
            colors.onSurfaceVariant to colors.background,
            colors.onSurface to colors.surfaceVariant,
            colors.onSurfaceVariant to colors.surfaceVariant,
            colors.primary to colors.surfaceVariant,
            colors.onPrimary to colors.primary,
            colors.primary to colors.surface,
            colors.onPrimaryContainer to colors.primaryContainer,
            colors.onSecondaryContainer to colors.secondaryContainer,
            colors.onTertiaryContainer to colors.tertiaryContainer,
            colors.onErrorContainer to colors.errorContainer,
            colors.onError to colors.error,
            colors.inverseOnSurface to colors.inverseSurface,
            status.expired.content to status.expired.container,
            status.expiringSoon.content to status.expiringSoon.container,
            status.fresh.content to status.fresh.container,
            status.shared.content to status.shared.container,
            status.personal.content to status.personal.container)

    pairs.forEach { (text, background) ->
      val ratio = contrast(text, background)
      assertTrue("Contrast $ratio of $text on $background is below $minimum", ratio >= minimum)
    }
  }

  private fun contrast(foreground: Color, background: Color): Double =
      ColorUtils.calculateContrast(foreground.toArgb(), background.toArgb())
}
