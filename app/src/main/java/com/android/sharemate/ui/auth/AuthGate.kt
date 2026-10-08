package com.android.sharemate.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.sharemate.model.auth.AuthSession

/** Keeps application content hidden until Firebase has restored or established a session. */
@Composable
fun AuthGate(
    viewModel: AuthViewModel,
    modifier: Modifier = Modifier,
    content: @Composable (AuthSession) -> Unit
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val session = state.session
  when {
    state.isRestoringSession ->
        AuthTheme(updateSystemBars = true) {
          Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              CircularProgressIndicator(Modifier.testTag("auth_restoring"))
            }
          }
        }
    session != null -> content(session)
    else -> AuthEntryFlow(viewModel, state, modifier)
  }
}

@Composable
private fun AuthEntryFlow(viewModel: AuthViewModel, state: AuthUiState, modifier: Modifier) {
  var showForm by rememberSaveable { mutableStateOf(false) }
  val goBack = {
    if (!state.isLoading) {
      viewModel.resetForm()
      showForm = false
    }
  }
  BackHandler(enabled = showForm, onBack = goBack)
  AuthTheme(updateSystemBars = true) {
    if (showForm) {
      AuthScreen(
          state = state,
          onEmailChange = viewModel::updateEmail,
          onPasswordChange = viewModel::updatePassword,
          onConfirmPasswordChange = viewModel::updateConfirmPassword,
          onSubmit = viewModel::submit,
          onSwitchMode = viewModel::switchMode,
          modifier = modifier,
          onBack = goBack)
    } else {
      AuthWelcomeScreen(
          onLogIn = {
            viewModel.selectMode(AuthMode.LOGIN)
            showForm = true
          },
          onCreateAccount = {
            viewModel.selectMode(AuthMode.SIGN_UP)
            showForm = true
          },
          modifier = modifier)
    }
  }
}
