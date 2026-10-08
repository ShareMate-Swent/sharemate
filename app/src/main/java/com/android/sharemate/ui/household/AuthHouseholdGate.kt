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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.android.sharemate.R
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.household.HouseholdRepository
import com.android.sharemate.ui.auth.AuthGate
import com.android.sharemate.ui.auth.AuthViewModel

/** Gives each signed-in user an isolated household flow, cleared when their session ends. */
@Composable
fun AuthHouseholdGate(
    authViewModel: AuthViewModel,
    authRepository: AuthRepository,
    householdRepository: HouseholdRepository,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
  AuthGate(authViewModel, modifier) { session ->
    key(session.uid) {
      val owner: HouseholdFlowStore =
          viewModel(
              key = "household_flow_${session.uid}",
              factory = viewModelFactory { initializer { HouseholdFlowStore() } },
          )
      val activity = LocalContext.current.findActivity()
      DisposableEffect(owner, activity) {
        onDispose { if (activity?.isChangingConfigurations != true) owner.viewModelStore.clear() }
      }
      CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        val factory = viewModelFactory {
          initializer { HouseholdGateViewModel(householdRepository, session.uid) }
          initializer { CreateHouseholdViewModel(householdRepository, authRepository) }
          initializer { JoinHouseholdViewModel(householdRepository, authRepository) }
        }
        val gateViewModel: HouseholdGateViewModel = viewModel(factory = factory)
        val state by gateViewModel.state.collectAsStateWithLifecycle()
        val navController = rememberNavController()
        val complete = {
          gateViewModel.refresh()
          navController.popBackStack("household_gate", inclusive = false)
          Unit
        }
        NavHost(
            navController = navController,
            startDestination = "household_gate",
            modifier = Modifier.fillMaxSize(),
        ) {
          composable("household_gate") {
            Column(Modifier.fillMaxSize()) {
              Box(Modifier.weight(1f)) {
                HouseholdGate(
                    viewModel = gateViewModel,
                    onCreateHousehold = { navController.navigate("household_create") },
                    onJoinHousehold = { navController.navigate("household_join") },
                ) {
                  content()
                }
              }
              if (state !is HouseholdGateState.Ready) {
                TextButton(
                    onClick = authViewModel::signOut,
                    modifier = Modifier.testTag("household_sign_out"),
                ) {
                  Text(stringResource(R.string.auth_sign_out))
                }
              }
            }
          }
          composable("household_create") {
            val createViewModel: CreateHouseholdViewModel = viewModel(factory = factory)
            val back = { if (!createViewModel.state.value.isLoading) complete() }
            CreateHouseholdScreen(
                viewModel = createViewModel,
                onContinue = complete,
                onBack = back,
            )
            BackHandler(onBack = back)
          }
          composable("household_join") {
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
