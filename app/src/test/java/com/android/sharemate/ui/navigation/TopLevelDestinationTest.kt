package com.android.sharemate.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopLevelDestinationTest {
  @Test
  fun definesAllBottomNavigationDestinations() {
    assertEquals(
        listOf(
            TopLevelDestination.FRIDGE.route,
            TopLevelDestination.RECIPES.route,
            TopLevelDestination.RECEIPTS.route,
            TopLevelDestination.CHAT.route,
            TopLevelDestination.SETTINGS.route),
        TopLevelDestination.entries.map { it.route },
    )
    assertEquals(
        listOf("Fridge", "Recipes", "Receipts", "Chat", "Settings"),
        TopLevelDestination.entries.map { it.textId },
    )
    assertEquals(
        listOf(
            NavigationTestTags.FRIDGE_TAB,
            NavigationTestTags.RECIPES_TAB,
            NavigationTestTags.RECEIPTS_TAB,
            NavigationTestTags.CHAT_TAB,
            NavigationTestTags.SETTINGS_TAB,
        ),
        TopLevelDestination.entries.map { it.testTag },
    )
  }

  @Test
  fun settingsOwnsItsNestedScreensButOtherTabsDoNot() {
    assertTrue(TopLevelDestination.SETTINGS.owns("settings"))
    assertTrue(TopLevelDestination.SETTINGS.owns(SettingsRoutes.NOTIFICATIONS))
    assertFalse(TopLevelDestination.FRIDGE.owns(SettingsRoutes.NOTIFICATIONS))
    assertFalse(TopLevelDestination.SETTINGS.owns(null))
  }
}
