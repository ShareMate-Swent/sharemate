package com.android.sharemate.model.auth

import kotlinx.coroutines.flow.Flow

/** Authentication identity only; household/profile data belongs to its own repository. */
data class AuthSession(val uid: String, val email: String?)

enum class AuthError {
  INVALID_EMAIL,
  INVALID_CREDENTIALS,
  WEAK_PASSWORD,
  EMAIL_IN_USE,
  NETWORK,
  TOO_MANY_REQUESTS,
  PROVIDER_DISABLED,
  UNKNOWN
}

/** A safe domain failure without Firebase messages or credentials. */
class AuthException(val error: AuthError) : Exception(error.name)

interface AuthRepository {
  /** Emits the restored session first, then every authentication change. */
  val session: Flow<AuthSession?>

  suspend fun signIn(email: String, password: String)

  suspend fun signUp(email: String, password: String)

  fun signOut()
}
