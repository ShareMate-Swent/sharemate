// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.auth.FirebaseAuthRepository
import com.android.sharemate.model.auth.FirestorePseudoRepository
import com.android.sharemate.model.barcode.BarcodeScanner
import com.android.sharemate.model.barcode.MlKitBarcodeScanner
import com.android.sharemate.model.chat.AndroidPendingFridgeActivityStore
import com.android.sharemate.model.chat.ChatRepository
import com.android.sharemate.model.chat.FirebaseChatRepository
import com.android.sharemate.model.finance.FirebaseExpenseSubmissionRepository
import com.android.sharemate.model.finance.FirebaseSettlementRepository
import com.android.sharemate.model.finance.FirestoreHouseholdFinanceRepository
import com.android.sharemate.model.finance.HouseholdFinanceRepository
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.HouseholdRepository
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.model.image.AndroidPersonalPhotoFiles
import com.android.sharemate.model.image.CommonsFoodImageRepository
import com.android.sharemate.model.image.FirebasePersonalPhotoRepository
import com.android.sharemate.model.image.FoodImageSelector
import com.android.sharemate.model.image.FridgeImageCache
import com.android.sharemate.model.incident.FirebaseIncidentPhotoRepository
import com.android.sharemate.model.item.FirebaseItemRepository
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.product.OpenFoodFactsProductCatalogRepository
import com.android.sharemate.model.product.ProductCatalogRepository
import com.android.sharemate.model.profile.FirebaseProfilePhotoRepository
import com.android.sharemate.model.profile.FirestoreUserProfileRepository
import com.android.sharemate.model.profile.ProfilePhotoRepository
import com.android.sharemate.model.profile.UserProfileRepository
import com.android.sharemate.model.receipt.FirebaseReceiptCloudRepository
import com.android.sharemate.model.receipt.ReceiptCloudRepository
import com.android.sharemate.model.user.ExpirationPreferencesProvider
import com.android.sharemate.model.user.ExpirationPreferencesRepository
import com.android.sharemate.model.user.FirebaseExpirationPreferencesRepository
import com.android.sharemate.model.user.FirebaseMemberNameRepository
import com.android.sharemate.model.user.FirebasePseudoRepository
import com.android.sharemate.model.user.MemberNameRepository
import com.android.sharemate.model.user.PseudoRepository
import com.android.sharemate.notifications.NotificationChannelRegistrar
import com.android.sharemate.notifications.NotificationPermission
import com.android.sharemate.notifications.NotificationScheduler
import com.android.sharemate.notifications.push.DeviceId
import com.android.sharemate.notifications.push.FirebaseCurrentUserIdProvider
import com.android.sharemate.notifications.push.PushTokenCleaningAuthRepository
import com.android.sharemate.notifications.push.PushTokenRefreshScheduler
import com.android.sharemate.notifications.push.PushTokenRegistrar
import com.android.sharemate.notifications.push.PushTokenRegistrarFactory
import com.android.sharemate.resources.C
import com.android.sharemate.ui.auth.AuthViewModel
import com.android.sharemate.ui.auth.CredentialManagerGoogleSignIn
import com.android.sharemate.ui.chat.ChatScreen
import com.android.sharemate.ui.chat.ChatViewModel
import com.android.sharemate.ui.finance.BALANCES_ROUTE
import com.android.sharemate.ui.finance.BalancesRoute
import com.android.sharemate.ui.finance.canViewBalances
import com.android.sharemate.ui.fridge.AddItemHost
import com.android.sharemate.ui.fridge.FridgeScreen
import com.android.sharemate.ui.fridge.FridgeViewModel
import com.android.sharemate.ui.household.AuthHouseholdGate
import com.android.sharemate.ui.household.HouseholdMembersScreen
import com.android.sharemate.ui.household.HouseholdMembersViewModel
import com.android.sharemate.ui.household.LeaveHouseholdViewModel
import com.android.sharemate.ui.navigation.AddItemMenuViewModel
import com.android.sharemate.ui.navigation.AddItemMethod
import com.android.sharemate.ui.navigation.AddItemSheet
import com.android.sharemate.ui.navigation.AppBottomBar
import com.android.sharemate.ui.navigation.AppDeepLinks
import com.android.sharemate.ui.navigation.ChildRoutes
import com.android.sharemate.ui.navigation.ProfileEntry
import com.android.sharemate.ui.navigation.ScreenTransitions
import com.android.sharemate.ui.navigation.TopLevelDestination
import com.android.sharemate.ui.navigation.cancelAddItem
import com.android.sharemate.ui.navigation.leaveSettings
import com.android.sharemate.ui.navigation.openDeepLink
import com.android.sharemate.ui.navigation.rememberFridgeEntry
import com.android.sharemate.ui.navigation.selectTab
import com.android.sharemate.ui.navigation.startAddItem
import com.android.sharemate.ui.profile.ProfilePhotoPicker
import com.android.sharemate.ui.profile.profilePhotoCamera
import com.android.sharemate.ui.receipts.ReceiptsScreen
import com.android.sharemate.ui.recipes.RecipesScreen
import com.android.sharemate.ui.settings.NotificationSettingsScreen
import com.android.sharemate.ui.settings.NotificationSettingsViewModel
import com.android.sharemate.ui.settings.SettingsScreen
import com.android.sharemate.ui.settings.SettingsViewModel
import com.android.sharemate.ui.settings.openSystemNotificationSettings
import com.android.sharemate.ui.settings.rememberSystemNotificationsAllowed
import com.android.sharemate.ui.startup.LaunchStage
import com.android.sharemate.ui.startup.LaunchStatus
import com.android.sharemate.ui.startup.LocalLaunchVisible
import com.android.sharemate.ui.startup.ReportLaunch
import com.android.sharemate.ui.startup.ShareMateLaunch
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

open class MainActivity : ComponentActivity() {
  private var pendingDeepLinkIntent by mutableStateOf<Intent?>(null)

  private val fridgeImageCache by lazy {
    val context = applicationContext
    processImageCache
        ?: FridgeImageCache(
                context = context,
                diskCache = FridgeImageCache.diskCache(File(context.cacheDir, "fridge-thumbnails")),
                storageBucket = profilePhotoBucket.orEmpty(),
                isCurrentUser = { FirebaseAuth.getInstance().currentUser?.uid == it })
            .also { processImageCache = it }
  }

  private val authRepository: PushTokenCleaningAuthRepository by lazy {
    PushTokenCleaningAuthRepository(
        delegate =
            FirebaseAuthRepository(
                FirebaseAuth.getInstance(),
                FirestorePseudoRepository(FirebaseFirestore.getInstance())),
        currentUser = FirebaseCurrentUserIdProvider(FirebaseAuth.getInstance()),
        registrar = pushTokenRegistrar,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        cleanupTimeoutMillis = PUSH_TOKEN_CLEANUP_TIMEOUT_MILLIS,
        beforeSignOut = { fridgeImageCache.purgeAccount(it) })
  }
  private val householdRepository: HouseholdRepository by lazy {
    HouseholdRepositoryFirestore(FirebaseFirestore.getInstance())
  }
  private val profileRepository: UserProfileRepository by lazy {
    FirestoreUserProfileRepository(FirebaseFirestore.getInstance())
  }

  private val chatRepository: ChatRepository by lazy {
    FirebaseChatRepository(
        FirebaseFirestore.getInstance(), FirebaseIncidentPhotoRepository.create())
  }
  private val pendingActivityStore by lazy { AndroidPendingFridgeActivityStore(applicationContext) }

  private val googleSignIn by lazy { CredentialManagerGoogleSignIn(applicationContext) }
  private val itemRepository: ItemRepository by lazy {
    FirebaseItemRepository(FirebaseFirestore.getInstance())
  }
  private val memberNameRepository: MemberNameRepository by lazy {
    FirebaseMemberNameRepository(FirebaseFirestore.getInstance())
  }
  private val pseudoRepository: PseudoRepository by lazy {
    FirebasePseudoRepository(FirebaseFirestore.getInstance())
  }
  private val expirationPreferencesRepository: ExpirationPreferencesRepository by lazy {
    FirebaseExpirationPreferencesRepository(FirebaseFirestore.getInstance())
  }
  private val expirationPreferencesProvider: ExpirationPreferencesProvider by lazy {
    ExpirationPreferencesProvider(expirationPreferencesRepository)
  }
  private val pushTokenRegistrar: PushTokenRegistrar by lazy {
    PushTokenRegistrarFactory.create(
        DeviceId.getOrCreate(applicationContext),
        FirebaseAuth.getInstance(),
        FirebaseFirestore.getInstance(),
        lazy { FirebaseMessaging.getInstance() })
  }

  protected open fun createFoodImageSelector() = FoodImageSelector(CommonsFoodImageRepository())

  protected open fun createHouseholdFinanceRepository(): HouseholdFinanceRepository =
      FirestoreHouseholdFinanceRepository(
          FirebaseFirestore.getInstance(),
          FirebaseSettlementRepository(
              FirebaseFunctions.getInstance(FirebaseSettlementRepository.REGION)))

  private val financeRepository by lazy { createHouseholdFinanceRepository() }
  private val expenseRepository by lazy {
    FirebaseExpenseSubmissionRepository(
        FirebaseFunctions.getInstance(FirebaseExpenseSubmissionRepository.REGION))
  }

  /** Creates the repository that uploads the photo picked after sign-up or in Settings. */
  protected open fun createProfilePhotoRepository(): ProfilePhotoRepository =
      FirebaseProfilePhotoRepository.create()

  private val profilePhotoRepository by lazy { createProfilePhotoRepository() }
  /**
   * Storage bucket of uploaded profile photos, the only one the members list loads from; null
   * without a Firebase app (as in UI tests), which shows Google photos only.
   */
  private val profilePhotoBucket by lazy {
    FirebaseApp.getApps(this).firstOrNull()?.options?.storageBucket
  }

  /** Creates authenticated receipt observation at the app composition root. */
  protected open fun createReceiptCloudRepository(): ReceiptCloudRepository =
      FirebaseReceiptCloudRepository(applicationContext)

  protected open fun createBarcodeScanner(): BarcodeScanner =
      MlKitBarcodeScanner(applicationContext)

  protected open fun createProductCatalogRepository(): ProductCatalogRepository =
      OpenFoodFactsProductCatalogRepository()

  private val authViewModel: AuthViewModel by viewModels {
    viewModelFactory {
      initializer {
        AuthViewModel(authRepository, authRepository.awaitingSignOut(googleSignIn::clearSession))
      }
    }
  }

  private val notificationPermissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    pendingDeepLinkIntent =
        if (savedInstanceState != null) {
          savedInstanceState.getString(PENDING_DEEP_LINK_URI)?.let {
            Intent(Intent.ACTION_VIEW, Uri.parse(it))
          }
        } else {
          intent.takeIf(Intent::isAppDeepLink)
        }
    NotificationChannelRegistrar.from(applicationContext).registerAll()
    NotificationScheduler.schedule(applicationContext)
    setContent {
      ShareMateLaunch {
        AuthHouseholdGate(
            authViewModel,
            authRepository,
            householdRepository,
            profileRepository,
            profilePhotoRepository,
            googleTokenProvider = { googleSignIn.getIdToken(this@MainActivity) },
            modifier =
                Modifier.fillMaxSize().semantics { testTag = C.Tag.main_screen_container }) {
                session,
                household,
                onMembershipChanged ->
              val navController = rememberNavController()
              val deepLinkIntent = pendingDeepLinkIntent
              LaunchedEffect(navController, deepLinkIntent) {
                deepLinkIntent?.let {
                  navController.openDeepLink(it, household.type)
                  if (pendingDeepLinkIntent === it) pendingDeepLinkIntent = null
                }
              }
              val launchVisible = LocalLaunchVisible.current
              LaunchedEffect(launchVisible) {
                if (!launchVisible && NotificationPermission.shouldRequest(this@MainActivity)) {
                  notificationPermissionLauncher.launch(NotificationPermission.PERMISSION)
                }
              }
              LaunchedEffect(session.uid) {
                PushTokenRefreshScheduler.enqueue(applicationContext, token = null)
              }
              DisposableEffect(navController) {
                onDispose {
                  // Session/household teardown cancels the retained fridge's image lookups.
                  if (!isChangingConfigurations) {
                    clearFridgeNavigation(navController)
                  }
                }
              }
              val currentRoute =
                  navController.currentBackStackEntryAsState().value?.destination?.route
              val transitions = remember { ScreenTransitions() }
              DisposableEffect(navController, transitions) {
                val detach = transitions.attach(navController)
                onDispose { detach() }
              }
              LaunchedEffect(transitions.heldRoute) { transitions.releaseHeldRoute() }
              val addItemMenu: AddItemMenuViewModel =
                  viewModel(key = "add_item_menu_${session.uid}_${household.id}")
              val addItemRequest by addItemMenu.request.collectAsStateWithLifecycle()
              val addItemSheetOpen by addItemMenu.isSheetOpen.collectAsStateWithLifecycle()
              if (addItemSheetOpen) {
                AddItemSheet(
                    onChoose = { navController.startAddItem(it, addItemMenu, transitions) },
                    onDismiss = addItemMenu::dismissSheet)
              }
              // The fridge is the start screen, so it stays below every tab and its form can open
              // over any of them.
              val fridgeEntry = navController.rememberFridgeEntry()
              if (fridgeEntry != null) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides fridgeEntry) {
                  AddItemHost(
                      viewModel = fridgeViewModel(fridgeEntry, session, household),
                      request = addItemRequest,
                      onRequestHandled = addItemMenu::requestHandled,
                      overOtherTab = currentRoute != TopLevelDestination.FRIDGE.route,
                      onSaved = { navController.selectTab(TopLevelDestination.FRIDGE) })
                }
              }
              if (currentRoute != null && currentRoute != TopLevelDestination.FRIDGE.route) {
                ReportLaunch(LaunchStage.DESTINATION, LaunchStatus.READY)
              }
              Scaffold(
                  modifier =
                      Modifier.fillMaxSize().semantics { testTag = C.Tag.main_screen_container },
                  bottomBar = {
                    if (TopLevelDestination.showsBottomBar(currentRoute)) {
                      AppBottomBar(
                          currentRoute = transitions.heldRoute ?: currentRoute,
                          tabs = TopLevelDestination.bottomBarFor(household.type),
                          onSelect = navController::selectTab,
                          onAdd = addItemMenu::openSheet)
                    }
                  },
              ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = TopLevelDestination.FRIDGE.route,
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    enterTransition = transitions.enter,
                    exitTransition = transitions.exit,
                ) {
                  composable(
                      TopLevelDestination.FRIDGE.route,
                      deepLinks = AppDeepLinks.navDeepLinks(TopLevelDestination.FRIDGE)) { entry ->
                        val fridgeViewModel = fridgeViewModel(entry, session, household)
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                          FridgeScreen(
                              viewModel = fridgeViewModel,
                              householdName = household.name,
                              headerTrailing = {
                                ProfileEntry(
                                    photoUrl = session.photoUrl,
                                    onClick = {
                                      navController.navigate(TopLevelDestination.SETTINGS.route) {
                                        launchSingleTop = true
                                      }
                                    })
                              })
                        }
                      }
                  composable(
                      TopLevelDestination.RECIPES.route,
                      deepLinks = AppDeepLinks.navDeepLinks(TopLevelDestination.RECIPES)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                          RecipesScreen()
                        }
                      }
                  composable(
                      TopLevelDestination.RECEIPTS.route,
                      deepLinks = AppDeepLinks.navDeepLinks(TopLevelDestination.RECEIPTS)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                          ReceiptsScreen(
                              itemRepository,
                              session.uid,
                              household.id,
                              household.type ==
                                  com.android.sharemate.model.household.FridgeType.SHARED,
                              showBalances = household.canViewBalances(session.uid),
                              onOpenBalances = { navController.navigate(BALANCES_ROUTE) },
                              expenseRepository = expenseRepository,
                              receiptCloudRepository = remember { createReceiptCloudRepository() },
                              profileRepository = profileRepository,
                              barcodeScanner = remember { createBarcodeScanner() },
                              productCatalogRepository =
                                  remember { createProductCatalogRepository() },
                              scanReceiptRequested = addItemRequest == AddItemMethod.RECEIPT,
                              onScanReceiptRequestHandled = {
                                addItemMenu.requestHandled(AddItemMethod.RECEIPT)
                              },
                              onScanReceiptCancelled = {
                                navController.cancelAddItem(addItemMenu, transitions)
                              })
                        }
                      }
                  composable(BALANCES_ROUTE) {
                    BalancesRoute(
                        financeRepository,
                        householdRepository,
                        authRepository,
                        household,
                        session.uid,
                        profileRepository,
                        onBack = { navController.popBackStack() })
                  }
                  // A private fridge has no chat: no tab, no route, so a chat deep link opens the
                  // fridge instead of a screen whose reads and writes the rules refuse.
                  if (TopLevelDestination.CHAT.isAvailableIn(household.type)) {
                    composable(
                        TopLevelDestination.CHAT.route,
                        deepLinks = AppDeepLinks.navDeepLinks(TopLevelDestination.CHAT)) {
                          val chatViewModel: ChatViewModel =
                              viewModel(
                                  key = "chat_${session.uid}_${household.id}",
                                  factory =
                                      viewModelFactory {
                                        initializer {
                                          ChatViewModel(
                                              chatRepository,
                                              household.id,
                                              session.uid,
                                              session.displayName
                                                  ?: getString(R.string.chat_default_sender_name),
                                              profileRepository,
                                              profilePhotoBucket)
                                        }
                                      })
                          ChatScreen(
                              chatViewModel,
                              householdName = household.name,
                              memberCount = household.memberIds.size)
                        }
                  }
                  composable(
                      TopLevelDestination.SETTINGS.route,
                      deepLinks = AppDeepLinks.navDeepLinks(TopLevelDestination.SETTINGS)) {
                        val leaveViewModel: LeaveHouseholdViewModel =
                            viewModel(
                                key = "leave_household_${household.id}",
                                factory =
                                    viewModelFactory {
                                      initializer {
                                        LeaveHouseholdViewModel(
                                            householdRepository, authRepository) {
                                              fridgeImageCache.purgeHousehold(it, household.id)
                                            }
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
                                            authRepository,
                                            householdRepository,
                                            pseudoRepository,
                                            household,
                                            profilePhotoRepository,
                                            AndroidPersonalPhotoFiles(applicationContext))
                                      }
                                    })
                        val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
                        val settingsScope = rememberCoroutineScope()
                        val photoCamera = profilePhotoCamera()
                        SettingsScreen(
                            state = settingsState,
                            leaveState = leaveState,
                            onLeaveHousehold = leaveViewModel::requestLeave,
                            onCancelLeave = leaveViewModel::cancel,
                            onConfirmLeave = leaveViewModel::confirmLeave,
                            onLeft = onMembershipChanged,
                            onShowMembers = {
                              navController.navigate(ChildRoutes.HOUSEHOLD_MEMBERS) {
                                launchSingleTop = true
                              }
                            },
                            onSwitchGoogleAccount = {
                              settingsScope.launch {
                                settingsViewModel.switchGoogleAccount {
                                  googleSignIn.getIdToken(this@MainActivity)
                                }
                              }
                            },
                            onPseudoChanged = settingsViewModel::updatePseudo,
                            onSavePseudo = settingsViewModel::savePseudo,
                            onRetryLoadPseudo = settingsViewModel::retryLoadPseudo,
                            onSignOut = authViewModel::signOut,
                            onOpenNotificationSettings = {
                              navController.navigate(ChildRoutes.NOTIFICATION_SETTINGS)
                            },
                            onChangePhoto = photoCamera::open,
                            onRetryPhotoUpload = settingsViewModel::retryPhotoUpload,
                            onBack = navController::leaveSettings)
                        ProfilePhotoPicker(
                            onPhotoConfirmed = settingsViewModel::changePhoto,
                            camera = photoCamera,
                            title = R.string.settings_change_photo)
                      }
                  composable(ChildRoutes.HOUSEHOLD_MEMBERS) {
                    // Scoped to this screen, so the members listener stops when it is closed.
                    val membersViewModel: HouseholdMembersViewModel =
                        viewModel(
                            factory =
                                viewModelFactory {
                                  initializer {
                                    HouseholdMembersViewModel(
                                        profileRepository,
                                        household.id,
                                        session.uid,
                                        profilePhotoBucket)
                                  }
                                })
                    val membersState by membersViewModel.state.collectAsStateWithLifecycle()
                    HouseholdMembersScreen(
                        state = membersState,
                        onRetry = membersViewModel::retry,
                        onBack = {
                          // Pops only this screen, even if Back is tapped twice.
                          navController.popBackStack(
                              ChildRoutes.HOUSEHOLD_MEMBERS, inclusive = true)
                        })
                  }
                  composable(ChildRoutes.NOTIFICATION_SETTINGS) {
                    val notificationViewModel: NotificationSettingsViewModel =
                        viewModel(
                            key = ChildRoutes.notificationSettingsViewModelKey(session.uid),
                            factory =
                                viewModelFactory {
                                  initializer {
                                    NotificationSettingsViewModel(
                                        expirationPreferencesRepository, session.uid)
                                  }
                                })
                    val notificationState by
                        notificationViewModel.state.collectAsStateWithLifecycle()
                    NotificationSettingsScreen(
                        state = notificationState,
                        isSystemAllowed = rememberSystemNotificationsAllowed(),
                        onBack = { navController.popBackStack() },
                        onOpenSystemSettings = {
                          openSystemNotificationSettings(this@MainActivity)
                        },
                        onSoonDaysChange = notificationViewModel::setSoonDays,
                        onSoonRepeatChange = notificationViewModel::setSoonRepeatDays,
                        onExpiredRepeatChange = notificationViewModel::setExpiredRepeatDays,
                        onRetryLoad = notificationViewModel::retryLoad)
                  }
                }
              }
            }
      }
    }
  }

  /** The fridge's ViewModel, kept by [owner], the fridge screen's entry, for its whole life. */
  @Composable
  private fun fridgeViewModel(
      owner: ViewModelStoreOwner,
      session: AuthSession,
      household: Household,
  ): FridgeViewModel =
      viewModel(
          viewModelStoreOwner = owner,
          key = "fridge_${session.uid}_${household.id}",
          factory =
              viewModelFactory {
                initializer {
                  FridgeViewModel(
                      itemRepository = itemRepository,
                      userId = session.uid,
                      householdId = household.id,
                      fridgeType = household.type,
                      foodImageSelector = createFoodImageSelector(),
                      barcodeScanner = createBarcodeScanner(),
                      productCatalogRepository = createProductCatalogRepository(),
                      photoStorage = FirebasePersonalPhotoRepository.create(),
                      photoFiles = AndroidPersonalPhotoFiles(applicationContext),
                      photoCache = fridgeImageCache.session(session.uid, household.id),
                      expirationPreferences = expirationPreferencesProvider,
                      pseudoRepository = pseudoRepository,
                      memberNameRepository = memberNameRepository,
                      currentUserDisplayName = session.displayName,
                      chatRepository = chatRepository,
                      pendingActivityStore = pendingActivityStore,
                      activitySenderName =
                          session.displayName
                              ?: session.email
                              ?: getString(R.string.chat_default_sender_name))
                }
              })

  public override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    if (intent.isAppDeepLink()) pendingDeepLinkIntent = intent
  }

  override fun onSaveInstanceState(outState: Bundle) {
    pendingDeepLinkIntent?.dataString?.let { outState.putString(PENDING_DEEP_LINK_URI, it) }
    super.onSaveInstanceState(outState)
  }

  private companion object {
    const val PENDING_DEEP_LINK_URI = "pending_deep_link_uri"
    var processImageCache: FridgeImageCache? = null

    /** Longest wait for the push token removal at sign-out, so an offline sign-out still ends. */
    const val PUSH_TOKEN_CLEANUP_TIMEOUT_MILLIS = 3_000L
  }
}

private fun Intent.isAppDeepLink(): Boolean =
    action == Intent.ACTION_VIEW &&
        data?.scheme == AppDeepLinks.SCHEME &&
        data?.host == AppDeepLinks.HOST

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
  // Only the routes in the graph: a private fridge registers no chat route.
  TopLevelDestination.entries
      .filter { navController.graph.findNode(it.route) != null }
      .forEach { navController.clearBackStack(it.route) }
  navController.popBackStack(navController.graph.id, inclusive = true)
}
