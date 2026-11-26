// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.android.sharemate.model.household.FridgeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppDeepLinksTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun buildsOneStableUriPerTopLevelDestination() {
    assertEquals(
        listOf(
            "sharemate://app/fridge",
            "sharemate://app/recipes",
            "sharemate://app/receipts",
            "sharemate://app/chat",
            "sharemate://app/settings"),
        TopLevelDestination.entries.map { AppDeepLinks.uri(it) })
  }

  @Test
  fun findsTheDestinationOfEachUriAndNothingElse() {
    TopLevelDestination.entries.forEach {
      assertEquals(it, AppDeepLinks.destinationOf(AppDeepLinks.uri(it)))
    }
    assertNull(AppDeepLinks.destinationOf("sharemate://app/chat/extra"))
    assertNull(AppDeepLinks.destinationOf("https://app/chat"))
    assertNull(AppDeepLinks.destinationOf(null))
  }

  @Test
  fun declaresTheSameUriAsItsNavDeepLink() {
    TopLevelDestination.entries.forEach { destination ->
      assertEquals(
          listOf(AppDeepLinks.uri(destination)),
          AppDeepLinks.navDeepLinks(destination).map { it.uriPattern })
    }
  }

  @Test
  fun everyUriNavigatesToItsDestination() {
    lateinit var navController: NavHostController
    compose.setContent {
      navController = rememberNavController()
      NavHost(navController, startDestination = "start") {
        composable("start") { Text("start") }
        TopLevelDestination.entries.forEach { destination ->
          composable(destination.route, deepLinks = AppDeepLinks.navDeepLinks(destination)) {
            Text(destination.route)
          }
        }
      }
    }

    TopLevelDestination.entries.forEach { destination ->
      compose.runOnUiThread { navController.navigate(Uri.parse(AppDeepLinks.uri(destination))) }
      compose.waitForIdle()
      assertEquals(destination.route, navController.currentBackStackEntry?.destination?.route)
    }
  }

  @Test
  fun inASharedFridgeEveryLinkStillOpensItsDestination() {
    lateinit var navController: NavHostController
    compose.setContent {
      navController = rememberNavController()
      NavHost(navController, startDestination = "start") {
        composable("start") { Text("start") }
        TopLevelDestination.entries.forEach { destination ->
          composable(destination.route, deepLinks = AppDeepLinks.navDeepLinks(destination)) {
            Text(destination.route)
          }
        }
      }
    }

    TopLevelDestination.entries.forEach { destination ->
      // Like a notification: no action, as the declared deep links have none.
      val intent =
          Intent()
              .setData(Uri.parse(AppDeepLinks.uri(destination)))
              .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      compose.runOnUiThread { navController.openDeepLink(intent, FridgeType.SHARED) }
      compose.waitForIdle()
      assertEquals(destination.route, navController.currentBackStackEntry?.destination?.route)
    }
  }
}
