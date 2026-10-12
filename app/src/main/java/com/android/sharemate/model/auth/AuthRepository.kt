// Co-authored-by: Codex
package com.android.sharemate.model.auth

import kotlinx.coroutines.flow.Flow

/** Authentication identity only; household/profile data belongs to its own repository. */
data class AuthSession(val uid: String, val email: String?)

// Keep these names aligned with AuthUiError: UI conversion uses valueOf(name).
enum class AuthError {
  INVALID_EMAIL,
  INVALID_CREDENTIALS,
  WEAK_PASSWORD,
  EMAIL_IN_USE,
  NETWORK,
  TOO_MANY_REQUESTS,
  PROVIDER_DISABLED,
  GOOGLE_UNAVAILABLE,
  ACCOUNT_CONFLICT,
  UNKNOWN
}

/** A safe domain failure without Firebase messages or credentials. */
class AuthException(val error: AuthError) : Exception(error.name)

interface AuthRepository {
  /** Emits the restored session first, then every authentication change. */
  val session: Flow<AuthSession?>

  suspend fun signIn(email: String, password: String)

  suspend fun signUp(email: String, password: String)

  /** Exchanges a Google ID token without storing it. Unsupported providers fail safely. */
  suspend fun signInWithGoogle(idToken: String) {
    throw AuthException(AuthError.PROVIDER_DISABLED)
  }

  fun signOut()
}
