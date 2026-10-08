// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.android.sharemate.model.auth.FirebaseAuthRepository
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.model.image.FoodImageSelector
import com.android.sharemate.model.image.OpenverseFoodImageRepository
import com.android.sharemate.model.item.FirebaseItemRepository
import com.android.sharemate.resources.C
import com.android.sharemate.ui.auth.AuthGate
import com.android.sharemate.ui.auth.AuthViewModel
import com.android.sharemate.ui.fridge.FridgeScreen
import com.android.sharemate.ui.fridge.FridgeViewModel
import com.android.sharemate.ui.navigation.TopLevelDestination
import com.android.sharemate.ui.receipts.ReceiptsScreen
import com.android.sharemate.ui.recipes.RecipesScreen
import com.android.sharemate.ui.settings.SettingsScreen
import com.android.sharemate.ui.theme.SampleAppTheme
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException

class MainActivity : ComponentActivity() {
  private val authViewModel: AuthViewModel by viewModels {
    viewModelFactory {
      initializer { AuthViewModel(FirebaseAuthRepository(FirebaseAuth.getInstance())) }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      SampleAppTheme(dynamicColor = false) {
        AuthGate(
            authViewModel,
            modifier = Modifier.fillMaxSize().semantics { testTag = C.Tag.main_screen_container }) {
              val authState by authViewModel.state.collectAsState()
              val navController = rememberNavController()
              val context = LocalContext.current
              val firestore =
                  remember(context) {
                    val app =
                        FirebaseApp.getApps(context).firstOrNull()
                            ?: FirebaseApp.initializeApp(context)
                    checkNotNull(app) { "Firebase configuration is unavailable." }
                    FirebaseFirestore.getInstance(app)
                  }
              val householdRepository = remember { HouseholdRepositoryFirestore(firestore) }
              val currentRoute =
                  navController.currentBackStackEntryAsState().value?.destination?.route
              Scaffold(
                  modifier =
                      Modifier.fillMaxSize().semantics { testTag = C.Tag.main_screen_container },
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
                    HouseholdFridgeContent(
                        userId = checkNotNull(authState.session).uid,
                        householdRepository = householdRepository,
                        firestore = firestore,
                    )
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
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                          SettingsScreen()
                          TextButton(onClick = authViewModel::signOut) {
                            Text(stringResource(R.string.auth_sign_out))
                          }
                        }
                  }
                }
              }
            }
      }
    }
  }
}

@Composable
private fun HouseholdFridgeContent(
    userId: String,
    householdRepository: HouseholdRepositoryFirestore,
    firestore: FirebaseFirestore,
) {
  var household by remember(userId) { mutableStateOf<Household?>(null) }
  var isLoading by remember(userId) { mutableStateOf(true) }
  var error by remember(userId) { mutableStateOf<Throwable?>(null) }

  LaunchedEffect(userId) {
    isLoading = true
    error = null
    try {
      household = householdRepository.getHouseholdForUser(userId)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      error = failure
    } finally {
      isLoading = false
    }
  }

  when {
    isLoading ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
    error != null ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text("Unable to load your household.")
        }
    else -> {
      val householdId = household?.id
      val itemRepository = remember(firestore) { FirebaseItemRepository(firestore) }
      val imageSelector = remember { FoodImageSelector(OpenverseFoodImageRepository()) }
      val fridgeViewModel: FridgeViewModel =
          viewModel(
              key = "fridge-$userId-${householdId ?: "private"}",
              factory =
                  viewModelFactory {
                    initializer {
                      FridgeViewModel(
                          itemRepository,
                          userId,
                          householdId,
                          imageSelector,
                      )
                    }
                  },
          )
      FridgeScreen(fridgeViewModel)
    }
  }
}
