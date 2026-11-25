// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState

/**
 * Opens [destination] from the bottom bar. Tapping the tab of the open screen, such as Receipts
 * from Balances, goes back to the tab itself instead of restoring that screen.
 */
internal fun NavController.selectTab(destination: TopLevelDestination) {
  if (destination.isParentOf(currentDestination?.route) &&
      popBackStack(destination.route, inclusive = false))
      return
  navigate(destination.route) {
    popUpTo(graph.findStartDestination().id) {
      // Retain the fridge ViewModel and its image jobs across tabs.
      saveState = true
    }
    launchSingleTop = true
    restoreState = true
  }
}

/**
 * Leaves Settings from its back arrow. When Settings is the only screen, as after a notification
 * opened it, the fridge opens instead of an empty screen. Ignored once Settings is closed, so a
 * double tap leaves only Settings.
 */
internal fun NavController.leaveSettings() {
  if (currentDestination?.route != TopLevelDestination.SETTINGS.route) return
  if (previousBackStackEntry != null) {
    popBackStack()
  } else {
    navigate(TopLevelDestination.FRIDGE.route) { popUpTo(graph.id) { inclusive = true } }
  }
}

/**
 * The fridge's back stack entry, which owns the fridge's ViewModel, or null when the fridge is not
 * in the back stack.
 *
 * It is looked up again whenever the open entry changes, not only its route: a fridge notification
 * opened on the fridge replaces its entry under the same route, and the old one is destroyed.
 */
@Composable
internal fun NavController.rememberFridgeEntry(): NavBackStackEntry? {
  val current by currentBackStackEntryAsState()
  return remember(current) {
    runCatching { getBackStackEntry(TopLevelDestination.FRIDGE.route) }.getOrNull()
  }
}
