// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.ui.finance.BALANCES_ROUTE
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
  private val transitions = ScreenTransitions()

  /** The app's routes that these tests visit, with the Settings deep link of a notification. */
  @Before
  fun setNavHost() {
    compose.setContent {
      nav = rememberNavController()
      fridgeEntry = nav.rememberFridgeEntry()
      DisposableEffect(nav) {
        val detach = transitions.attach(nav)
        onDispose { detach() }
      }
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

  private fun linkTo(destination: TopLevelDestination) =
      Intent()
          .setData(Uri.parse(AppDeepLinks.uri(destination)))
          .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

  // This graph has no chat route, like that of a private fridge.
  @Test
  fun chatLinkInAPrivateFridgeOpensTheFridgeFromAnotherTab() {
    compose.runOnIdle { nav.selectTab(TopLevelDestination.RECEIPTS) }
    compose.runOnIdle { nav.openDeepLink(linkTo(TopLevelDestination.CHAT), FridgeType.PRIVATE) }
    compose.runOnIdle { assertEquals(TopLevelDestination.FRIDGE.route, route()) }
  }

  @Test
  fun otherLinksInAPrivateFridgeStillOpenTheirScreen() {
    compose.runOnIdle { nav.selectTab(TopLevelDestination.RECEIPTS) }
    compose.runOnIdle { nav.openDeepLink(linkTo(TopLevelDestination.SETTINGS), FridgeType.PRIVATE) }
    compose.runOnIdle { assertEquals(TopLevelDestination.SETTINGS.route, route()) }
  }

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

  @Test
  fun choosingAReceiptFromBalancesOpensReceiptsWithTheRequestAndClosesTheSheet() {
    val menu = AddItemMenuViewModel().apply { openSheet() }
    compose.runOnIdle {
      nav.selectTab(TopLevelDestination.RECEIPTS)
      nav.navigate(BALANCES_ROUTE)
    }
    compose.runOnIdle { nav.startAddItem(AddItemMethod.RECEIPT, menu, transitions) }
    compose.runOnIdle {
      assertEquals(TopLevelDestination.RECEIPTS.route, route())
      assertEquals(AddItemMethod.RECEIPT, menu.request.value)
      assertFalse(menu.isSheetOpen.value)
    }
  }

  @Test
  fun cancellingAReceiptScanStartedOnTheFridgeGoesBackToTheFridgeOnce() {
    val menu = AddItemMenuViewModel()
    compose.runOnIdle { nav.startAddItem(AddItemMethod.RECEIPT, menu, transitions) }
    compose.runOnIdle { assertEquals(TopLevelDestination.RECEIPTS.route, route()) }
    compose.runOnIdle { nav.cancelAddItem(menu, transitions) }
    compose.runOnIdle { assertEquals(TopLevelDestination.FRIDGE.route, route()) }

    // The way back is used: a later cancel on Receipts stays there.
    compose.runOnIdle { nav.selectTab(TopLevelDestination.RECEIPTS) }
    compose.runOnIdle { nav.cancelAddItem(menu, transitions) }
    compose.runOnIdle { assertEquals(TopLevelDestination.RECEIPTS.route, route()) }
  }

  @Test
  fun cancellingOnTheTabOfTheAddButtonStaysThere() {
    val menu = AddItemMenuViewModel()
    compose.runOnIdle { nav.selectTab(TopLevelDestination.RECEIPTS) }
    compose.runOnIdle {
      nav.startAddItem(AddItemMethod.RECEIPT, menu, transitions)
      nav.cancelAddItem(menu, transitions)
    }
    compose.runOnIdle { assertEquals(TopLevelDestination.RECEIPTS.route, route()) }
  }

  @Test
  fun choosingTheFridgeFormFromReceiptsStaysOnReceiptsWithTheRequest() {
    val menu = AddItemMenuViewModel()
    compose.runOnIdle { nav.selectTab(TopLevelDestination.RECEIPTS) }
    for (method in listOf(AddItemMethod.MANUAL, AddItemMethod.BARCODE)) {
      compose.runOnIdle { nav.startAddItem(method, menu, transitions) }
      compose.runOnIdle {
        // The form opens over Receipts; only a saved item opens the fridge.
        assertEquals(TopLevelDestination.RECEIPTS.route, route())
        assertEquals(method, menu.request.value)
      }
    }
  }

  @Test
  fun addButtonFlowsSwitchTabsWithoutAFadeOnlyOnce() {
    val menu = AddItemMenuViewModel()
    val fridge = compose.runOnIdle { checkNotNull(nav.currentBackStackEntry) }
    compose.runOnIdle { nav.startAddItem(AddItemMethod.RECEIPT, menu, transitions) }
    val receipts = compose.runOnIdle { checkNotNull(nav.currentBackStackEntry) }
    compose.runOnIdle {
      assertTrue(transitions.isInstant(receipts))
      // The fridge stays in front until the receipt photo covers the switch.
      assertTrue(transitions.opensUnderFlow(receipts))
    }

    compose.runOnIdle { nav.cancelAddItem(menu, transitions) }
    compose.runOnIdle {
      val back = checkNotNull(nav.currentBackStackEntry)
      assertEquals(TopLevelDestination.FRIDGE.route, back.destination.route)
      assertTrue(transitions.isInstant(back))
      // No dialog covers the way back: the tab shows at once.
      assertFalse(transitions.opensUnderFlow(back))
      assertFalse(transitions.isInstant(receipts))
    }

    // A tab tapped afterwards fades as usual, back to the fridge too.
    compose.runOnIdle { nav.selectTab(TopLevelDestination.RECEIPTS) }
    compose.runOnIdle {
      assertFalse(transitions.isInstant(checkNotNull(nav.currentBackStackEntry)))
    }
    compose.runOnIdle { nav.selectTab(TopLevelDestination.FRIDGE) }
    compose.runOnIdle {
      assertFalse(transitions.isInstant(checkNotNull(nav.currentBackStackEntry)))
      assertFalse(transitions.isInstant(fridge))
    }
  }

  @Test
  fun bottomBarKeepsThePreviousTabUntilTheFlowCoversTheSwitch() = runTest {
    val held = ScreenTransitions()
    held.openUnderFlow(entry = null, previousRoute = TopLevelDestination.CHAT.route)
    assertEquals(TopLevelDestination.CHAT.route, held.heldRoute)
    launch { held.releaseHeldRoute() }
    advanceTimeBy(399)
    assertEquals(TopLevelDestination.CHAT.route, held.heldRoute)
    advanceTimeBy(2)
    assertNull(held.heldRoute)
    // Nothing held: returns at once.
    held.releaseHeldRoute()
    assertNull(held.heldRoute)
  }
}
