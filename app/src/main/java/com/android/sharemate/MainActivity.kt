package com.android.sharemate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.android.sharemate.resources.C
import com.android.sharemate.ui.fridge.FridgeScreen
import com.android.sharemate.ui.navigation.TopLevelDestination
import com.android.sharemate.ui.receipts.ReceiptsScreen
import com.android.sharemate.ui.recipes.RecipesScreen
import com.android.sharemate.ui.settings.SettingsScreen
import com.android.sharemate.ui.theme.SampleAppTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      SampleAppTheme(dynamicColor = false) {
        val navController = rememberNavController()
        val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

        Surface(
            modifier = Modifier.fillMaxSize().semantics { testTag = C.Tag.main_screen_container },
            color = MaterialTheme.colorScheme.background) {
              Scaffold(
                  bottomBar = {
                    NavigationBar {
                      TopLevelDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            modifier = Modifier.testTag(destination.testTag),
                            selected = currentRoute == destination.route,
                            onClick = {
                              navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                  saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                              }
                            },
                            icon = {
                              when (destination) {
                                TopLevelDestination.FRIDGE -> Icon(Icons.Filled.Kitchen, "Fridge")
                                TopLevelDestination.RECIPES ->
                                    Icon(Icons.Filled.MenuBook, "Recipes")
                                TopLevelDestination.RECEIPTS ->
                                    Icon(Icons.Filled.ReceiptLong, "Receipts")
                                TopLevelDestination.SETTINGS ->
                                    Icon(Icons.Filled.Settings, "Settings")
                              }
                            },
                            label = { Text(destination.textId) },
                        )
                      }
                    }
                  },
              ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = TopLevelDestination.FRIDGE.route,
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                ) {
                  composable(TopLevelDestination.FRIDGE.route) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                      FridgeScreen()
                    }
                  }
                  composable(TopLevelDestination.RECIPES.route) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                      RecipesScreen()
                    }
                  }
                  composable(TopLevelDestination.RECEIPTS.route) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                      ReceiptsScreen()
                    }
                  }
                  composable(TopLevelDestination.SETTINGS.route) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                      SettingsScreen()
                    }
                  }
                }
              }
            }
      }
    }
  }
}
