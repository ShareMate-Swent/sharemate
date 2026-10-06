package com.android.sharemate.ui.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Keeps application content hidden until Firebase has restored or established a session. */
@Composable
fun AuthGate(
    viewModel: AuthViewModel,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  when {
    state.isRestoringSession ->
        AuthTheme(updateSystemBars = true) {
          Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              CircularProgressIndicator(Modifier.testTag("auth_restoring"))
            }
          }
        }
    state.session != null -> content()
    else ->
        AuthTheme(updateSystemBars = true) {
          AuthScreen(
              state = state,
              onEmailChange = viewModel::updateEmail,
              onPasswordChange = viewModel::updatePassword,
              onConfirmPasswordChange = viewModel::updateConfirmPassword,
              onSubmit = viewModel::submit,
              onSwitchMode = viewModel::switchMode,
              modifier = modifier)
        }
  }
}
