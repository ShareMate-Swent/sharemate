// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.household

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.android.sharemate.R
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.household.HouseholdRepository
import com.android.sharemate.model.image.AndroidPersonalPhotoFiles
import com.android.sharemate.model.profile.ProfilePhotoRepository
import com.android.sharemate.model.profile.UserProfileRepository
import com.android.sharemate.ui.auth.AuthGate
import com.android.sharemate.ui.auth.AuthViewModel
import com.android.sharemate.ui.profile.ProfilePhotoStepScreen
import com.android.sharemate.ui.profile.ProfilePhotoViewModel
import com.android.sharemate.ui.startup.LaunchStage
import com.android.sharemate.ui.startup.LaunchStatus
import com.android.sharemate.ui.startup.LocalLaunchAttempt
import com.android.sharemate.ui.startup.ReportLaunch

/**
 * Gives each signed-in user an isolated household flow, cleared when their session ends. A user who
 * just signed up by email first sees the optional profile photo step.
 *
 * @param profilePhotoRepository Uploads the photo picked in the profile photo step.
 * @param content The application, shown once the user belongs to a household. It receives that
 *   session, household and a callback to invoke when membership may have changed (e.g. after
 *   leaving), so the gate looks it up again and shows onboarding if needed.
 */
@Composable
fun AuthHouseholdGate(
    authViewModel: AuthViewModel,
    authRepository: AuthRepository,
    householdRepository: HouseholdRepository,
    profileRepository: UserProfileRepository,
    profilePhotoRepository: ProfilePhotoRepository,
    googleTokenProvider: suspend () -> String?,
    modifier: Modifier = Modifier,
    content:
        @Composable
        (session: AuthSession, household: Household, onMembershipChanged: () -> Unit) -> Unit,
) {
  AuthGate(authViewModel, modifier, googleTokenProvider) { session ->
    key(session.uid) {
      val owner: HouseholdFlowStore =
          viewModel(
              key = "household_flow_${session.uid}_${LocalLaunchAttempt.current}",
              factory = viewModelFactory { initializer { HouseholdFlowStore() } },
          )
      val context = LocalContext.current
      val activity = context.findActivity()
      DisposableEffect(owner, activity) {
        onDispose { if (activity?.isChangingConfigurations != true) owner.viewModelStore.clear() }
      }
      CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        val factory = viewModelFactory {
          initializer { HouseholdGateViewModel(householdRepository, profileRepository, session) }
          initializer {
            CreateHouseholdViewModel(householdRepository, authRepository, createSavedStateHandle())
          }
          initializer { JoinHouseholdViewModel(householdRepository, authRepository) }
          initializer {
            ProfilePhotoViewModel(
                profilePhotoRepository,
                AndroidPersonalPhotoFiles(context.applicationContext),
                session.uid)
          }
        }
        // Decided once per session; the navigation back stack then keeps the current screen.
        val startDestination = rememberSaveable {
          if (authViewModel.state.value.profilePhotoStepUid == session.uid) HouseholdRoutes.PHOTO
          else HouseholdRoutes.GATE
        }
        val gateViewModel: HouseholdGateViewModel = viewModel(factory = factory)
        val state by gateViewModel.state.collectAsStateWithLifecycle()
        ReportLaunch(
            LaunchStage.HOUSEHOLD,
            when {
              startDestination == HouseholdRoutes.PHOTO -> LaunchStatus.READY
              state is HouseholdGateState.Ready -> LaunchStatus.CONTINUE
              state is HouseholdGateState.NeedsHousehold -> LaunchStatus.READY
              // The error destination is usable: it exposes retry and account sign-out.
              state is HouseholdGateState.Error -> LaunchStatus.READY
              else -> LaunchStatus.WAITING
            })
        LaunchedEffect(session) { gateViewModel.updateSession(session) }
        val navController = rememberNavController()
        val complete = {
          gateViewModel.refresh()
          navController.popBackStack(HouseholdRoutes.GATE, inclusive = false)
          Unit
        }
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.fillMaxSize(),
        ) {
          // Inside the NavHost, so the photo ViewModels get the entry's saved state.
          composable(HouseholdRoutes.PHOTO) {
            ProfilePhotoStepScreen(
                viewModel = viewModel(factory = factory),
                onDone = {
                  authViewModel.finishProfilePhotoStep()
                  navController.navigate(HouseholdRoutes.GATE) {
                    popUpTo(HouseholdRoutes.PHOTO) { inclusive = true }
                  }
                },
            )
          }
          composable(HouseholdRoutes.GATE) {
            Column(Modifier.fillMaxSize()) {
              Box(Modifier.weight(1f)) {
                HouseholdGate(
                    viewModel = gateViewModel,
                    onCreateHousehold = { navController.navigate(HouseholdRoutes.CREATE) },
                    onJoinHousehold = { navController.navigate(HouseholdRoutes.JOIN) },
                ) { household ->
                  key(household.id) { content(session, household, gateViewModel::refresh) }
                }
              }
              if (state !is HouseholdGateState.Ready) {
                TextButton(
                    onClick = authViewModel::signOut,
                    modifier = Modifier.testTag(HouseholdGateTestTags.SIGN_OUT),
                ) {
                  Text(stringResource(R.string.auth_sign_out))
                }
              }
            }
          }
          composable(HouseholdRoutes.CREATE) {
            val createViewModel: CreateHouseholdViewModel = viewModel(factory = factory)
            val back = { if (!createViewModel.state.value.isLoading) complete() }
            CreateHouseholdScreen(
                viewModel = createViewModel,
                onContinue = complete,
                onBack = back,
            )
            BackHandler(onBack = back)
          }
          composable(HouseholdRoutes.JOIN) {
            val joinViewModel: JoinHouseholdViewModel = viewModel(factory = factory)
            val back = { if (!joinViewModel.state.value.isLoading) complete() }
            JoinHouseholdScreen(
                viewModel = joinViewModel,
                onJoined = complete,
                onBack = back,
            )
            BackHandler(onBack = back)
          }
        }
      }
    }
  }
}

/** Retains the onboarding ViewModels across rotation, but clears them when the session ends. */
private class HouseholdFlowStore : ViewModel(), ViewModelStoreOwner {
  override val viewModelStore = ViewModelStore()

  override fun onCleared() {
    viewModelStore.clear()
  }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
      is Activity -> this
      is ContextWrapper -> baseContext.findActivity()
      else -> null
    }

internal object HouseholdRoutes {
  const val GATE = "household_gate"
  const val CREATE = "household_create"
  const val JOIN = "household_join"
  const val PHOTO = "profile_photo"
}
