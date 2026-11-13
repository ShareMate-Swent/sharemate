// Co-authored-by: Codex
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.auth.AuthError
import com.android.sharemate.model.auth.AuthException
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.text.TextLimits
import com.android.sharemate.model.text.limitedTo
import com.android.sharemate.model.user.PseudoValidationError
import com.android.sharemate.model.user.trimPseudoPadding
import com.android.sharemate.model.user.validatePseudo as validateModelPseudo
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
  /** Sign-up requires a pseudo containing at least one visible character. */
  PSEUDO_REQUIRED,
  /** The pseudo is already claimed by another account. */
  PSEUDO_TAKEN,
  PSEUDO_TOO_LONG,
  PASSWORD_REQUIRED,
  WEAK_PASSWORD,
  /** A new password is longer than [TextLimits.PASSWORD]. */
  PASSWORD_TOO_LONG,
  PASSWORD_MISMATCH,
  INVALID_CREDENTIALS,
  EMAIL_IN_USE,
  NETWORK,
  TOO_MANY_REQUESTS,
  PROVIDER_DISABLED,
  GOOGLE_UNAVAILABLE,
  ACCOUNT_CONFLICT,
  UNKNOWN
}

data class AuthUiState(
    val email: String = "",
    val pseudo: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val mode: AuthMode = AuthMode.LOGIN,
    val isLoading: Boolean = false,
    val isRestoringSession: Boolean = true,
    val session: AuthSession? = null,
    val pendingProfileUpdate: PendingProfileUpdate? = null,
    val error: AuthUiError? = null
)

/** A profile name that still needs to be saved for the signed-in user. */
data class PendingProfileUpdate(val uid: String, val displayName: String)

class AuthViewModel(
    private val repository: AuthRepository,
    private val clearCredentialState: suspend () -> Unit = {},
) : ViewModel() {
  private val mutableState = MutableStateFlow(AuthUiState())
  val state = mutableState.asStateFlow()

  init {
    viewModelScope.launch {
      repository.session.collect { session ->
        mutableState.update {
          val updatedSession = session?.let { observed -> mergeDisplayName(it.session, observed) }
          val pendingProfileUpdate =
              it.pendingProfileUpdate?.takeIf { pending -> updatedSession?.uid == pending.uid }
          it.copy(
              session = updatedSession,
              pendingProfileUpdate = pendingProfileUpdate,
              isRestoringSession = false,
              password = if (updatedSession != null) "" else it.password,
              confirmPassword = if (updatedSession != null) "" else it.confirmPassword,
              error =
                  if (updatedSession != null && pendingProfileUpdate == null) null else it.error)
        }
      }
    }
  }

  /** Updates the email, cut to [TextLimits.EMAIL] characters so a paste cannot exceed it. */
  fun updateEmail(value: String) = edit {
    it.copy(email = value.limitedTo(TextLimits.EMAIL), error = null)
  }

  fun updatePseudo(value: String) = edit { it.copy(pseudo = value, error = null) }

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
    if (current.isLoading || current.isRestoringSession) return
    current.pendingProfileUpdate?.let { pending ->
      val pseudo = current.pseudo.trimPseudoPadding()
      val pseudoError = validatePseudo(pseudo)
      if (pseudoError != null) {
        mutableState.update { it.copy(error = pseudoError) }
      } else if (current.session?.uid == pending.uid) {
        retryPendingProfileUpdate(pending.copy(displayName = pseudo))
      }
      return
    }
    if (current.session != null) return
    val email = current.email.trim()
    val pseudo = current.pseudo.trimPseudoPadding()
    val pseudoError = if (current.mode == AuthMode.SIGN_UP) validatePseudo(pseudo) else null
    val error =
        when {
          !EMAIL_PATTERN.matches(email) -> AuthUiError.INVALID_EMAIL
          pseudoError != null -> pseudoError
          current.password.isEmpty() -> AuthUiError.PASSWORD_REQUIRED
          current.mode == AuthMode.SIGN_UP ->
              PasswordRules.signUpError(current.password, current.confirmPassword)
          else -> null
        }
    if (error != null) {
      mutableState.update { it.copy(error = error) }
      return
    }
    mutableState.update { it.copy(email = email, isLoading = true, error = null) }
    viewModelScope.launch {
      try {
        if (current.mode == AuthMode.LOGIN) {
          repository.signIn(email, current.password)
          // Keep submissions disabled until the observed session reaches UI state.
          state.first { it.session != null }
        } else {
          repository.signUp(email, current.password, pseudo)
          val observedSession = state.first { it.session != null }.session!!
          mutableState.update {
            it.withDisplayNameForUid(observedSession.uid, pseudo).copy(pendingProfileUpdate = null)
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        val recovered = current.mode == AuthMode.SIGN_UP && retryProfileUpdateIfSignedIn(pseudo)
        if (!recovered) mutableState.update { it.copy(error = failure.toUiError()) }
      } finally {
        mutableState.update { it.copy(isLoading = false) }
      }
    }
  }

  private suspend fun retryProfileUpdateIfSignedIn(pseudo: String): Boolean {
    val observedSession = state.value.session ?: return false
    return try {
      repository.updateCurrentUserDisplayName(observedSession.uid, pseudo)
      markProfileUpdated(observedSession.uid, pseudo)
      true
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      mutableState.update { currentState ->
        if (currentState.session?.uid != observedSession.uid) currentState
        else
            currentState.copy(
                pseudo = pseudo,
                pendingProfileUpdate = PendingProfileUpdate(observedSession.uid, pseudo))
      }
      false
    }
  }

  private fun retryPendingProfileUpdate(pending: PendingProfileUpdate) {
    mutableState.update {
      it.copy(
          pseudo = pending.displayName,
          pendingProfileUpdate = pending,
          isLoading = true,
          error = null)
    }
    viewModelScope.launch {
      try {
        repository.updateCurrentUserDisplayName(pending.uid, pending.displayName)
        markProfileUpdated(pending.uid, pending.displayName)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        mutableState.update { currentState ->
          if (currentState.session?.uid != pending.uid) currentState
          else currentState.copy(error = failure.toUiError())
        }
      } finally {
        mutableState.update { it.copy(isLoading = false) }
      }
    }
  }

  /** Records a saved display name for [uid] and clears any pending retry, if still signed in. */
  private fun markProfileUpdated(uid: String, displayName: String) {
    mutableState.update { currentState ->
      val updatedState = currentState.withDisplayNameForUid(uid, displayName)
      if (updatedState === currentState) currentState
      else updatedState.copy(pendingProfileUpdate = null, error = null)
    }
  }

  /**
   * The picker runs in the caller's UI scope; only the token exchange uses the retained VM scope.
   */
  suspend fun signInWithGoogle(tokenProvider: suspend () -> String?) {
    val current = state.value
    if (current.isLoading || current.isRestoringSession || current.session != null) return
    mutableState.update { it.copy(isLoading = true, error = null) }
    var handedOff = false
    try {
      val token = tokenProvider() ?: return
      if (token.isBlank()) throw AuthException(AuthError.INVALID_CREDENTIALS)
      viewModelScope.launch { exchangeGoogleToken(token) }
      handedOff = true
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: AuthException) {
      mutableState.update { it.copy(error = failure.error.toUiError()) }
    } catch (_: Exception) {
      mutableState.update { it.copy(error = AuthUiError.GOOGLE_UNAVAILABLE) }
    } finally {
      if (!handedOff) mutableState.update { it.copy(isLoading = false) }
    }
  }

  private suspend fun exchangeGoogleToken(token: String) {
    try {
      repository.signInWithGoogle(token)
      val session = state.first { it.session != null }.session!!
      val pseudo = session.displayName?.trimPseudoPadding().orEmpty()
      val pseudoError = validatePseudo(pseudo)
      if (pseudoError != null) {
        mutableState.update { currentState ->
          if (currentState.session?.uid != session.uid) currentState
          else
              currentState.copy(
                  pseudo = pseudo,
                  pendingProfileUpdate = PendingProfileUpdate(session.uid, pseudo),
                  error = pseudoError)
        }
      } else {
        try {
          repository.updateCurrentUserDisplayName(session.uid, pseudo)
          markProfileUpdated(session.uid, pseudo)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (failure: Exception) {
          preservePendingPseudo(session.uid, pseudo, failure.toUiError())
        }
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      mutableState.update { it.copy(error = failure.toUiError()) }
    } finally {
      mutableState.update { it.copy(isLoading = false) }
    }
  }

  private fun preservePendingPseudo(uid: String, pseudo: String, error: AuthUiError) {
    mutableState.update { currentState ->
      if (currentState.session?.uid != uid) currentState
      else
          currentState.copy(
              pseudo = pseudo,
              pendingProfileUpdate = PendingProfileUpdate(uid, pseudo),
              error = error)
    }
  }

  fun signOut() {
    if (state.value.isLoading) return
    repository.signOut()
    mutableState.update { AuthUiState(isRestoringSession = false, isLoading = true) }
    viewModelScope.launch {
      try {
        clearCredentialState()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        // Firebase has already signed out; provider cleanup must not prevent logout.
      } finally {
        mutableState.update { it.copy(isLoading = false) }
      }
    }
  }

  private companion object {
    val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
  }
}

private fun AuthError.toUiError(): AuthUiError = AuthUiError.valueOf(name)

private fun Exception.toUiError(): AuthUiError =
    (this as? AuthException)?.error?.toUiError() ?: AuthUiError.UNKNOWN

/** Keeps a non-blank display name for the same user when a refresh omits it. */
internal fun mergeDisplayName(current: AuthSession?, observed: AuthSession): AuthSession {
  val displayName =
      observed.displayName?.takeIf { it.isNotBlank() }
          ?: current?.takeIf { it.uid == observed.uid }?.displayName?.takeIf { it.isNotBlank() }
  return observed.copy(displayName = displayName)
}

/** Updates a session only while the UI still holds the user that initiated the operation. */
internal fun AuthUiState.withDisplayNameForUid(uid: String, displayName: String): AuthUiState {
  val currentSession = session?.takeIf { it.uid == uid } ?: return this
  return copy(session = currentSession.copy(displayName = displayName))
}

/** Returns the validation error for a sign-up pseudo, or null when it is acceptable. */
internal fun validatePseudo(pseudo: String): AuthUiError? =
    when (validateModelPseudo(pseudo)) {
      PseudoValidationError.REQUIRED -> AuthUiError.PSEUDO_REQUIRED
      PseudoValidationError.TOO_LONG -> AuthUiError.PSEUDO_TOO_LONG
      null -> null
    }
