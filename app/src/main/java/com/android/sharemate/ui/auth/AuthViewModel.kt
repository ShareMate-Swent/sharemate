package com.android.sharemate.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.auth.AuthError
import com.android.sharemate.model.auth.AuthException
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthMode {
  LOGIN,
  SIGN_UP
}

// Keep repository error names aligned with AuthError for valueOf(name); validation errors are
// UI-only.
enum class AuthUiError {
  INVALID_EMAIL,
  PASSWORD_REQUIRED,
  WEAK_PASSWORD,
  PASSWORD_MISMATCH,
  INVALID_CREDENTIALS,
  EMAIL_IN_USE,
  NETWORK,
  TOO_MANY_REQUESTS,
  PROVIDER_DISABLED,
  UNKNOWN
}

data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val mode: AuthMode = AuthMode.LOGIN,
    val isLoading: Boolean = false,
    val isRestoringSession: Boolean = true,
    val session: AuthSession? = null,
    val error: AuthUiError? = null
)

class AuthViewModel(private val repository: AuthRepository) : ViewModel() {
  private val mutableState = MutableStateFlow(AuthUiState())
  val state = mutableState.asStateFlow()

  init {
    viewModelScope.launch {
      repository.session.collect { session ->
        mutableState.update {
          it.copy(
              session = session,
              isRestoringSession = false,
              password = if (session != null) "" else it.password,
              confirmPassword = if (session != null) "" else it.confirmPassword,
              error = if (session != null) null else it.error)
        }
      }
    }
  }

  fun updateEmail(value: String) = edit { it.copy(email = value, error = null) }

  fun updatePassword(value: String) = edit { it.copy(password = value, error = null) }

  fun updateConfirmPassword(value: String) = edit { it.copy(confirmPassword = value, error = null) }

  fun switchMode() = edit {
    it.copy(
        mode = if (it.mode == AuthMode.LOGIN) AuthMode.SIGN_UP else AuthMode.LOGIN,
        password = "",
        confirmPassword = "",
        error = null)
  }

  fun selectMode(mode: AuthMode) = edit {
    it.copy(mode = mode, password = "", confirmPassword = "", error = null)
  }

  fun resetForm() = edit { it.copy(password = "", confirmPassword = "", error = null) }

  private fun edit(transform: (AuthUiState) -> AuthUiState) {
    if (!state.value.isLoading) mutableState.update(transform)
  }

  fun submit() {
    val current = state.value
    if (current.isLoading || current.isRestoringSession || current.session != null) return
    val email = current.email.trim()
    val error =
        when {
          !EMAIL_PATTERN.matches(email) -> AuthUiError.INVALID_EMAIL
          current.password.isEmpty() -> AuthUiError.PASSWORD_REQUIRED
          current.mode == AuthMode.SIGN_UP && current.password.length < MIN_PASSWORD_LENGTH ->
              AuthUiError.WEAK_PASSWORD
          current.mode == AuthMode.SIGN_UP && current.password != current.confirmPassword ->
              AuthUiError.PASSWORD_MISMATCH
          else -> null
        }
    if (error != null) {
      mutableState.update { it.copy(error = error) }
      return
    }
    mutableState.update { it.copy(email = email, isLoading = true, error = null) }
    viewModelScope.launch {
      try {
        if (current.mode == AuthMode.LOGIN) repository.signIn(email, current.password)
        else repository.signUp(email, current.password)
        // Keep submissions disabled until the observed session reaches UI state.
        state.first { it.session != null }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: AuthException) {
        mutableState.update { it.copy(error = failure.error.toUiError()) }
      } catch (failure: Exception) {
        mutableState.update { it.copy(error = AuthUiError.UNKNOWN) }
      } finally {
        mutableState.update { it.copy(isLoading = false) }
      }
    }
  }

  fun signOut() {
    if (!state.value.isLoading) {
      repository.signOut()
      mutableState.update { AuthUiState(isRestoringSession = false) }
    }
  }

  private companion object {
    const val MIN_PASSWORD_LENGTH = 6
    val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
  }
}

private fun AuthError.toUiError(): AuthUiError = AuthUiError.valueOf(name)
