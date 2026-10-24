// Co-authored-by: Codex
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.auth

import kotlinx.coroutines.flow.Flow

/** How the user signed in. [UNKNOWN] covers providers the app does not offer. */
enum class SignInProvider {
  GOOGLE,
  EMAIL,
  UNKNOWN
}

/**
 * The authenticated identity, as reported by the sign-in provider. Household data belongs to its
 * own repository.
 *
 * @property uid Unique user identifier.
 * @property email Account email, if the provider shares one.
 * @property displayName Account name, e.g. from the Google profile; null for email accounts.
 * @property photoUrl Account photo URL, e.g. from the Google profile; null if there is none.
 * @property provider How the user signed in.
 */
data class AuthSession(
    val uid: String,
    val email: String?,
    val displayName: String? = null,
    val photoUrl: String? = null,
    val provider: SignInProvider = SignInProvider.UNKNOWN,
)

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

  /** Exchanges a Google ID token without storing it. */
  suspend fun signInWithGoogle(idToken: String)

  fun signOut()
}
