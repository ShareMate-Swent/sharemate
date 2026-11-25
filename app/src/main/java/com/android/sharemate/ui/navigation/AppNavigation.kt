// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.delay

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
 * Starts [method], chosen in the sheet of the add button: closes the sheet and asks the screen of
 * [method] to start it. Unless [method] opens over the current tab, it also opens that screen's tab
 * behind the current one, until the flow's dialog covers it ([ScreenTransitions.openUnderFlow]);
 * the open tab is then kept for [cancelAddItem].
 */
internal fun NavController.startAddItem(
    method: AddItemMethod,
    menu: AddItemMenuViewModel,
    transitions: ScreenTransitions,
) {
  val route = currentDestination?.route
  menu.choose(method, from = TopLevelDestination.bottomBar.firstOrNull { it.isSelected(route) })
  if (method.opensOverCurrentTab) return
  selectTab(method.destination)
  transitions.openUnderFlow(currentBackStackEntry, previousRoute = route)
}

/**
 * Goes back, without a fade, to the tab the user was on when tapping the add button, after they
 * cancelled what they chose, such as the receipt photo. Otherwise the screen of the flow, which the
 * user never looked at, would show while fading out. Nothing happens when they are already on it.
 *
 * @return Whether it left the open screen.
 */
internal fun NavController.cancelAddItem(
    menu: AddItemMenuViewModel,
    transitions: ScreenTransitions
): Boolean {
  val origin = menu.cancelled() ?: return false
  if (origin.isSelected(currentDestination?.route)) return false
  selectTab(origin)
  transitions.openAtOnce(currentBackStackEntry)
  return true
}

/**
 * The transitions of the app's [androidx.navigation.compose.NavHost]: the default fade, except for
 * the screen given to [openAtOnce] or [openUnderFlow], which replaces the previous one without a
 * fade. That lasts until the next navigation, so the same screen fades again later.
 *
 * Give [enter] and [exit] to the NavHost and call [onNavigated] on each navigation, as [attach]
 * does.
 */
internal class ScreenTransitions {
  private var instantEntryId: String? = null
  private var underFlow = false

  /** Shows [entry], the screen just navigated to, at once instead of fading it in. */
  fun openAtOnce(entry: NavBackStackEntry?) {
    instantEntryId = entry?.id
    underFlow = false
  }

  /**
   * The route the bottom bar shows as selected while a screen opens under a flow: the previous
   * screen's, as that screen is still in front. Null otherwise.
   */
  var heldRoute: String? by mutableStateOf(null)
    private set

  /**
   * Opens [entry], the screen just navigated to, behind the previous screen, which stays in front
   * for [FLOW_COVER_MILLIS]: long enough for the dialog that [entry] opens, such as the camera, to
   * cover the switch. Without it, [entry] would show alone before its dialog appears. The bottom
   * bar keeps [previousRoute] selected for as long, through [heldRoute] and [releaseHeldRoute].
   */
  fun openUnderFlow(entry: NavBackStackEntry?, previousRoute: String?) {
    instantEntryId = entry?.id
    underFlow = true
    heldRoute = previousRoute
  }

  /** Waits until the flow covers the switch, then lets the bottom bar show the open screen. */
  suspend fun releaseHeldRoute() {
    if (heldRoute == null) return
    delay(FLOW_COVER_MILLIS.toLong())
    heldRoute = null
  }

  /** Whether opening [entry] skips the fade. */
  fun isInstant(entry: NavBackStackEntry): Boolean = entry.id == instantEntryId

  /**
   * Whether opening [entry] keeps the previous screen in front until a dialog covers the switch.
   */
  fun opensUnderFlow(entry: NavBackStackEntry): Boolean = isInstant(entry) && underFlow

  /** Ends the skip once another screen is opened. */
  fun onNavigated(current: NavBackStackEntry?) {
    if (current?.id != instantEntryId) {
      instantEntryId = null
      underFlow = false
      heldRoute = null
    }
  }

  /** Calls [onNavigated] on each navigation of [nav]; returns the function that stops it. */
  fun attach(nav: NavController): () -> Unit {
    val listener =
        NavController.OnDestinationChangedListener { controller, _, _ ->
          onNavigated(controller.currentBackStackEntry)
        }
    nav.addOnDestinationChangedListener(listener)
    return { nav.removeOnDestinationChangedListener(listener) }
  }

  /** How the opened screen appears. */
  val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    when {
      opensUnderFlow(targetState) -> fadeIn(snap(FLOW_COVER_MILLIS))
      isInstant(targetState) -> EnterTransition.None
      else -> fadeIn(tween(FADE_MILLIS))
    }
  }

  /** How the previous screen leaves. */
  val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    when {
      opensUnderFlow(targetState) -> fadeOut(snap(FLOW_COVER_MILLIS))
      isInstant(targetState) -> ExitTransition.None
      else -> fadeOut(tween(FADE_MILLIS))
    }
  }

  private companion object {
    /** Length of the fade, the NavHost default. */
    const val FADE_MILLIS = 700

    /** How long the previous screen stays in front of one that opens a dialog right away. */
    const val FLOW_COVER_MILLIS = 400
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
