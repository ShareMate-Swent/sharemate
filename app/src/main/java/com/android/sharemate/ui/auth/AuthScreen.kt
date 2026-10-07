package com.android.sharemate.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.android.sharemate.R

@Composable
fun AuthScreen(
    state: AuthUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSwitchMode: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
  AuthTheme {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      AuthForm(
          state,
          onEmailChange,
          onPasswordChange,
          onConfirmPasswordChange,
          onSubmit,
          onSwitchMode,
          onBack)
    }
  }
}

@Composable
private fun AuthForm(
    state: AuthUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSwitchMode: () -> Unit,
    onBack: (() -> Unit)?
) {
  val signingUp = state.mode == AuthMode.SIGN_UP
  Column(
      modifier =
          Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally) {
        onBack?.let {
          TextButton(
              onClick = it,
              enabled = !state.isLoading,
              modifier = Modifier.align(Alignment.Start).testTag("auth_back")) {
                Text(stringResource(R.string.auth_back))
              }
        }
        Text(stringResource(R.string.auth_title), style = MaterialTheme.typography.headlineLarge)
        Text(
            stringResource(if (signingUp) R.string.auth_sign_up else R.string.auth_log_in),
            style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = state.email,
            onValueChange = onEmailChange,
            label = { Text(stringResource(R.string.auth_email)) },
            enabled = !state.isLoading,
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag("auth_email"))
        PasswordField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = stringResource(R.string.auth_password),
            enabled = !state.isLoading,
            tag = "auth_password",
            mode = state.mode,
            imeAction = if (signingUp) ImeAction.Next else ImeAction.Done,
            onSubmit = onSubmit)
        if (signingUp) {
          PasswordField(
              value = state.confirmPassword,
              onValueChange = onConfirmPasswordChange,
              label = stringResource(R.string.auth_confirm_password),
              enabled = !state.isLoading,
              tag = "auth_confirm_password",
              mode = state.mode,
              imeAction = ImeAction.Done,
              onSubmit = onSubmit)
          Text(
              stringResource(R.string.auth_password_hint),
              style = MaterialTheme.typography.bodySmall)
        }
        state.error?.let {
          Text(
              text = stringResource(it.messageResource()),
              color = MaterialTheme.colorScheme.error,
              modifier =
                  Modifier.testTag("auth_error").semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (state.isLoading || state.isRestoringSession)
            CircularProgressIndicator(Modifier.testTag("auth_loading"))
        Button(
            onClick = onSubmit,
            enabled = !state.isLoading && !state.isRestoringSession,
            modifier = Modifier.fillMaxWidth().testTag("auth_submit")) {
              Text(stringResource(if (signingUp) R.string.auth_sign_up else R.string.auth_log_in))
            }
        TextButton(
            onClick = onSwitchMode,
            enabled = !state.isLoading,
            modifier = Modifier.testTag("auth_switch_mode")) {
              Text(
                  stringResource(
                      if (signingUp) R.string.auth_have_account else R.string.auth_need_account))
            }
      }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    tag: String,
    mode: AuthMode,
    imeAction: ImeAction,
    onSubmit: () -> Unit
) {
  var visible by remember(mode) { mutableStateOf(false) }
  OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      label = { Text(label) },
      enabled = enabled,
      singleLine = true,
      visualTransformation =
          if (visible) VisualTransformation.None else PasswordVisualTransformation(),
      trailingIcon = {
        TextButton(onClick = { visible = !visible }, enabled = enabled) {
          Text(
              stringResource(
                  if (visible) R.string.auth_hide_password else R.string.auth_show_password))
        }
      },
      keyboardOptions =
          KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
      keyboardActions = KeyboardActions(onDone = { onSubmit() }),
      modifier = Modifier.fillMaxWidth().testTag(tag))
}

private fun AuthUiError.messageResource(): Int =
    when (this) {
      AuthUiError.INVALID_EMAIL -> R.string.auth_error_email
      AuthUiError.PASSWORD_REQUIRED -> R.string.auth_error_password_required
      AuthUiError.WEAK_PASSWORD -> R.string.auth_error_weak_password
      AuthUiError.PASSWORD_MISMATCH -> R.string.auth_error_password_mismatch
      AuthUiError.INVALID_CREDENTIALS -> R.string.auth_error_credentials
      AuthUiError.EMAIL_IN_USE -> R.string.auth_error_email_in_use
      AuthUiError.NETWORK -> R.string.auth_error_network
      AuthUiError.TOO_MANY_REQUESTS -> R.string.auth_error_rate_limit
      AuthUiError.PROVIDER_DISABLED -> R.string.auth_error_provider_disabled
      AuthUiError.UNKNOWN -> R.string.auth_error_unknown
    }
