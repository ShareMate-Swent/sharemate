// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.navigation

import com.android.sharemate.R
import com.android.sharemate.ui.finance.BALANCES_ROUTE
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
        listOf(
            R.string.fridge_title,
            R.string.recipes_title,
            R.string.receipts_title,
            R.string.chat_title,
            R.string.settings_title),
        TopLevelDestination.entries.map { it.labelRes },
    )
    assertEquals(
        listOf(
            NavigationTestTags.FRIDGE_TAB,
            NavigationTestTags.RECIPES_TAB,
            NavigationTestTags.RECEIPTS_TAB,
            NavigationTestTags.CHAT_TAB,
            NavigationTestTags.PROFILE_ENTRY,
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

  @Test
  fun theBottomBarShowsOnlyOnItsTabsAndTheirScreens() {
    TopLevelDestination.bottomBar.forEach {
      assertTrue(TopLevelDestination.showsBottomBar(it.route))
    }
    assertTrue(TopLevelDestination.showsBottomBar(BALANCES_ROUTE))
    assertFalse(TopLevelDestination.showsBottomBar(TopLevelDestination.SETTINGS.route))
    assertFalse(TopLevelDestination.showsBottomBar(ChildRoutes.HOUSEHOLD_MEMBERS))
    assertFalse(TopLevelDestination.showsBottomBar(ChildRoutes.NOTIFICATION_SETTINGS))
    // A screen that belongs to no tab has no bar until it is added to one.
    assertFalse(TopLevelDestination.showsBottomBar("new-screen"))
    assertFalse(TopLevelDestination.showsBottomBar(null))
  }
}
