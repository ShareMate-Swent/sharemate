// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.android.sharemate.ui.finance.BALANCES_ROUTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppNavigationTest {
  @get:Rule val compose = createComposeRule()

  private lateinit var nav: NavHostController
  private var fridgeEntry: NavBackStackEntry? = null

  /** The app's routes that these tests visit, with the Settings deep link of a notification. */
  @Before
  fun setNavHost() {
    compose.setContent {
      nav = rememberNavController()
      fridgeEntry = nav.rememberFridgeEntry()
      NavHost(nav, startDestination = TopLevelDestination.FRIDGE.route) {
        composable(
            TopLevelDestination.FRIDGE.route,
            deepLinks = AppDeepLinks.navDeepLinks(TopLevelDestination.FRIDGE)) {}
        composable(TopLevelDestination.RECEIPTS.route) {}
        composable(BALANCES_ROUTE) {}
        composable(
            TopLevelDestination.SETTINGS.route,
            deepLinks = AppDeepLinks.navDeepLinks(TopLevelDestination.SETTINGS)) {}
      }
    }
  }

  private fun route() = nav.currentDestination?.route

  @Test
  fun tappingReceiptsFromBalancesShowsReceipts() {
    compose.runOnIdle {
      nav.selectTab(TopLevelDestination.RECEIPTS)
      nav.navigate(BALANCES_ROUTE)
    }
    compose.runOnIdle { nav.selectTab(TopLevelDestination.RECEIPTS) }
    compose.runOnIdle { assertEquals(TopLevelDestination.RECEIPTS.route, route()) }

    // Balances is gone, so leaving the tab and coming back does not restore it either.
    compose.runOnIdle { nav.selectTab(TopLevelDestination.FRIDGE) }
    compose.runOnIdle { nav.selectTab(TopLevelDestination.RECEIPTS) }
    compose.runOnIdle { assertEquals(TopLevelDestination.RECEIPTS.route, route()) }
  }

  @Test
  fun backArrowOfSettingsReturnsToTheFridgeOnceEvenIfTappedTwice() {
    compose.runOnIdle { nav.navigate(TopLevelDestination.SETTINGS.route) }
    compose.runOnIdle {
      nav.leaveSettings()
      nav.leaveSettings()
    }
    compose.runOnIdle {
      assertEquals(TopLevelDestination.FRIDGE.route, route())
      assertNull(nav.previousBackStackEntry)
    }
  }

  @Test
  fun backArrowOfSettingsOpenedByANotificationShowsTheFridge() {
    compose.runOnIdle {
      // Notifications open the app with these flags, not as a new task. The intent has no action
      // because the declared deep links have none, and Navigation requires both to match.
      val intent =
          Intent()
              .setData(Uri.parse(AppDeepLinks.uri(TopLevelDestination.SETTINGS)))
              .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      assertTrue(nav.handleDeepLink(intent))
    }
    compose.runOnIdle {
      assertEquals(TopLevelDestination.SETTINGS.route, route())
      // Settings is alone: popping it would leave an empty screen.
      assertNull(nav.previousBackStackEntry)
      nav.leaveSettings()
      nav.leaveSettings()
    }
    compose.runOnIdle {
      assertEquals(TopLevelDestination.FRIDGE.route, route())
      assertNull(nav.previousBackStackEntry)
    }
  }

  @Test
  fun fridgeEntryFollowsAFridgeNotificationOpenedOnTheFridge() {
    val before = compose.runOnIdle { checkNotNull(fridgeEntry) }
    compose.runOnIdle {
      val intent =
          Intent()
              .setData(Uri.parse(AppDeepLinks.uri(TopLevelDestination.FRIDGE)))
              .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      assertTrue(nav.handleDeepLink(intent))
    }
    compose.runOnIdle {
      val live = nav.getBackStackEntry(TopLevelDestination.FRIDGE.route)
      // The notification replaced the entry under the same route: the old one is gone.
      assertNotSame(before, live)
      assertSame(live, fridgeEntry)
      assertNotEquals(Lifecycle.State.DESTROYED, live.lifecycle.currentState)
    }
  }

  @Test
  fun fridgeEntryStaysBelowTheOtherTabs() {
    val fridge = compose.runOnIdle { checkNotNull(fridgeEntry) }
    compose.runOnIdle { nav.selectTab(TopLevelDestination.RECEIPTS) }
    compose.runOnIdle {
      assertEquals(TopLevelDestination.RECEIPTS.route, route())
      assertSame(fridge, fridgeEntry)
    }
  }
}
