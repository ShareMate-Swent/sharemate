// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.navigation

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
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
            "sharemate://app/settings"),
        TopLevelDestination.entries.map { AppDeepLinks.uri(it) })
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
}
