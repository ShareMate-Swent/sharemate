package com.android.sharemate.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class TopLevelDestinationTest {
  @Test
  fun definesAllBottomNavigationDestinations() {
    assertEquals(
        listOf(
            TopLevelDestination.FRIDGE.route,
            TopLevelDestination.RECIPES.route,
            TopLevelDestination.RECEIPTS.route,
            TopLevelDestination.BALANCES.route,
            TopLevelDestination.SETTINGS.route),
        TopLevelDestination.entries.map { it.route },
    )
    assertEquals(
        listOf("Fridge", "Recipes", "Receipts", "Balances", "Settings"),
        TopLevelDestination.entries.map { it.textId },
    )
    assertEquals(
        listOf(
            NavigationTestTags.FRIDGE_TAB,
            NavigationTestTags.RECIPES_TAB,
            NavigationTestTags.RECEIPTS_TAB,
            NavigationTestTags.BALANCES_TAB,
            NavigationTestTags.SETTINGS_TAB,
        ),
        TopLevelDestination.entries.map { it.testTag },
    )
  }
}
