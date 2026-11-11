// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
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
  fun aTabStaysSelectedOnTheScreensOpenedFromIt() {
    val settings = TopLevelDestination.SETTINGS

    assertTrue(settings.isSelected("settings"))
    assertTrue(settings.isSelected(ChildRoutes.HOUSEHOLD_MEMBERS))
    assertTrue(settings.isParentOf(ChildRoutes.HOUSEHOLD_MEMBERS))
    // The tab itself is not its own child, and a route that only shares the prefix is another tab.
    assertFalse(settings.isParentOf("settings"))
    assertFalse(settings.isSelected("settingsextra"))
    assertFalse(settings.isSelected(null))
    assertEquals(
        listOf(TopLevelDestination.SETTINGS),
        TopLevelDestination.entries.filter { it.isSelected(ChildRoutes.HOUSEHOLD_MEMBERS) })
    assertTrue(settings.isSelected(ChildRoutes.NOTIFICATION_SETTINGS))
    assertTrue(settings.isParentOf(ChildRoutes.NOTIFICATION_SETTINGS))
    assertFalse(TopLevelDestination.FRIDGE.isSelected(ChildRoutes.NOTIFICATION_SETTINGS))
  }
}
