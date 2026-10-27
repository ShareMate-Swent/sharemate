// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.FirebaseAuthRepository
import com.android.sharemate.model.barcode.BarcodeScanner
import com.android.sharemate.model.barcode.MlKitBarcodeScanner
import com.android.sharemate.model.household.HouseholdRepository
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.model.image.FoodImageSelector
import com.android.sharemate.model.image.OpenverseFoodImageRepository
import com.android.sharemate.model.item.FirebaseItemRepository
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.product.OpenFoodFactsProductCatalogRepository
import com.android.sharemate.model.product.ProductCatalogRepository
import com.android.sharemate.resources.C
import com.android.sharemate.ui.auth.AuthViewModel
import com.android.sharemate.ui.auth.CredentialManagerGoogleSignIn
import com.android.sharemate.ui.fridge.FridgeScreen
import com.android.sharemate.ui.fridge.FridgeViewModel
import com.android.sharemate.ui.household.AuthHouseholdGate
import com.android.sharemate.ui.household.LeaveHouseholdViewModel
import com.android.sharemate.ui.navigation.TopLevelDestination
import com.android.sharemate.ui.receipts.ReceiptsScreen
import com.android.sharemate.ui.recipes.RecipesScreen
import com.android.sharemate.ui.settings.SettingsScreen
import com.android.sharemate.ui.settings.SettingsViewModel
import com.android.sharemate.ui.theme.SampleAppTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

open class MainActivity : ComponentActivity() {
  private val authRepository: AuthRepository by lazy {
    FirebaseAuthRepository(FirebaseAuth.getInstance())
  }
  private val householdRepository: HouseholdRepository by lazy {
    HouseholdRepositoryFirestore(FirebaseFirestore.getInstance())
  }

  private val googleSignIn by lazy { CredentialManagerGoogleSignIn(applicationContext) }
  private val itemRepository: ItemRepository by lazy {
    FirebaseItemRepository(FirebaseFirestore.getInstance())
  }

  protected open fun createFoodImageSelector() = FoodImageSelector(OpenverseFoodImageRepository())

  protected open fun createBarcodeScanner(): BarcodeScanner =
      MlKitBarcodeScanner(applicationContext)

  protected open fun createProductCatalogRepository(): ProductCatalogRepository =
      OpenFoodFactsProductCatalogRepository()

  private val authViewModel: AuthViewModel by viewModels {
    viewModelFactory { initializer { AuthViewModel(authRepository, googleSignIn::clearSession) } }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      SampleAppTheme(dynamicColor = false) {
        AuthHouseholdGate(
            authViewModel,
            authRepository,
            householdRepository,
            googleTokenProvider = { googleSignIn.getIdToken(this@MainActivity) },
            modifier =
                Modifier.fillMaxSize().semantics { testTag = C.Tag.main_screen_container }) {
                session,
                household,
                onMembershipChanged ->
              val navController = rememberNavController()
              DisposableEffect(navController) {
                onDispose {
                  if (!isChangingConfigurations) {
                    clearFridgeNavigation(navController)
                  }
                }
              }
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
                  composable(TopLevelDestination.FRIDGE.route) { entry ->
                    val fridgeViewModel: FridgeViewModel =
                        viewModel(
                            viewModelStoreOwner = entry,
                            key = "fridge_${session.uid}_${household.id}",
                            factory =
                                viewModelFactory {
                                  initializer {
                                    FridgeViewModel(
                                        itemRepository = itemRepository,
                                        userId = session.uid,
                                        householdId = household.id,
                                        foodImageSelector = createFoodImageSelector(),
                                        barcodeScanner = createBarcodeScanner(),
                                        productCatalogRepository = createProductCatalogRepository())
                                  }
                                })
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                      FridgeScreen(viewModel = fridgeViewModel)
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
                    val leaveViewModel: LeaveHouseholdViewModel =
                        viewModel(
                            key = "leave_household_${household.id}",
                            factory =
                                viewModelFactory {
                                  initializer {
                                    LeaveHouseholdViewModel(householdRepository, authRepository)
                                  }
                                })
                    val leaveState by leaveViewModel.state.collectAsStateWithLifecycle()
                    val settingsViewModel: SettingsViewModel =
                        viewModel(
                            key = "settings_${session.uid}_${household.id}",
                            factory =
                                viewModelFactory {
                                  initializer {
                                    SettingsViewModel(
                                        authRepository, householdRepository, household)
                                  }
                                })
                    val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
                    val settingsScope = rememberCoroutineScope()
                    SettingsScreen(
                        state = settingsState,
                        leaveState = leaveState,
                        onLeaveHousehold = leaveViewModel::requestLeave,
                        onCancelLeave = leaveViewModel::cancel,
                        onConfirmLeave = leaveViewModel::confirmLeave,
                        onLeft = onMembershipChanged,
                        onSwitchGoogleAccount = {
                          settingsScope.launch {
                            settingsViewModel.switchGoogleAccount {
                              googleSignIn.getIdToken(this@MainActivity)
                            }
                          }
                        },
                        onSignOut = authViewModel::signOut)
                  }
                }
              }
            }
      }
    }
  }
}

internal fun clearFridgeNavigation(navController: NavHostController) {
  val fridgeEntry =
      try {
        navController.getBackStackEntry(TopLevelDestination.FRIDGE.route)
      } catch (_: IllegalArgumentException) {
        null
      }
  if (fridgeEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.CREATED) == true) {
    fridgeEntry.viewModelStore.clear()
  }
  TopLevelDestination.entries.forEach { navController.clearBackStack(it.route) }
  navController.popBackStack(navController.graph.id, inclusive = true)
}
