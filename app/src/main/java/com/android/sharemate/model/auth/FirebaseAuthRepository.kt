// Co-authored-by: Codex
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.auth

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await

class FirebaseAuthRepository(
    private val auth: FirebaseAuth,
    private val pseudoRepository: PseudoRepository,
) : AuthRepository {
  override val session: Flow<AuthSession?> =
      callbackFlow {
            val listener =
                FirebaseAuth.AuthStateListener { firebaseAuth ->
                  trySend(firebaseAuth.currentUser?.toAuthSession())
                }
            auth.addAuthStateListener(listener)
            awaitClose { auth.removeAuthStateListener(listener) }
          }
          .distinctUntilChanged()

  override suspend fun signIn(email: String, password: String) {
    authenticate { auth.signInWithEmailAndPassword(email, password).await() }
  }

  override suspend fun signUp(email: String, password: String, displayName: String) {
    authenticate {
      val user =
          auth.createUserWithEmailAndPassword(email, password).await().user
              ?: throw IllegalStateException(CREATED_USER_MISSING_MESSAGE)
      pseudoRepository.claim(user.uid, displayName)
      user.updateProfile(profileChangeRequest(displayName)).await()
    }
  }

  override suspend fun updateCurrentUserDisplayName(expectedUid: String, displayName: String) {
    authenticate {
      val user = auth.currentUser ?: throw IllegalStateException(NO_SIGNED_IN_USER_MESSAGE)
      if (user.uid != expectedUid) throw IllegalStateException(SIGNED_IN_USER_CHANGED_MESSAGE)
      val previousDisplayName = user.displayName
      pseudoRepository.claim(user.uid, displayName)
      user.updateProfile(profileChangeRequest(displayName)).await()
      if (previousDisplayName != null &&
          normalizePseudo(previousDisplayName) != normalizePseudo(displayName)) {
        pseudoRepository.release(user.uid, previousDisplayName)
      }
    }
  }

  override suspend fun signInWithGoogle(idToken: String) {
    if (idToken.isBlank()) throw AuthException(AuthError.INVALID_CREDENTIALS)
    authenticate {
      auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
    }
  }

  override fun signOut() = auth.signOut()

  private fun profileChangeRequest(displayName: String) =
      UserProfileChangeRequest.Builder().setDisplayName(displayName).build()

  private suspend fun <T> authenticate(action: suspend () -> T): T {
    try {
      return action()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: AuthException) {
      throw failure
    } catch (failure: Exception) {
      val error =
          when (failure) {
            is FirebaseNetworkException -> AuthError.NETWORK
            is FirebaseTooManyRequestsException -> AuthError.TOO_MANY_REQUESTS
            is FirebaseAuthException -> authErrorForCode(failure.errorCode)
            else -> AuthError.UNKNOWN
          }
      throw AuthException(error)
    }
  }

  private companion object {
    const val CREATED_USER_MISSING_MESSAGE = "Firebase did not return the created user"
    const val NO_SIGNED_IN_USER_MESSAGE = "No Firebase user is signed in"
    const val SIGNED_IN_USER_CHANGED_MESSAGE = "The signed-in Firebase user changed"
  }
}

/** Blank profile values are treated as missing so the UI can fall back to the email. */
internal fun FirebaseUser.toAuthSession(): AuthSession {
  val providerIds = providerData.map { it.providerId }
  return AuthSession(
      uid = uid,
      email = email,
      displayName = displayName?.takeIf { it.isNotBlank() },
      photoUrl = photoUrl?.toString()?.takeIf { it.isNotBlank() },
      provider =
          when {
            GoogleAuthProvider.PROVIDER_ID in providerIds -> SignInProvider.GOOGLE
            EmailAuthProvider.PROVIDER_ID in providerIds -> SignInProvider.EMAIL
            else -> SignInProvider.UNKNOWN
          },
  )
}

internal fun authErrorForCode(code: String): AuthError =
    when (code) {
      FirebaseAuthErrorCodes.INVALID_EMAIL -> AuthError.INVALID_EMAIL
      FirebaseAuthErrorCodes.WRONG_PASSWORD,
      FirebaseAuthErrorCodes.USER_NOT_FOUND,
      FirebaseAuthErrorCodes.INVALID_CREDENTIAL,
      FirebaseAuthErrorCodes.INVALID_LOGIN_CREDENTIALS,
      FirebaseAuthErrorCodes.USER_DISABLED,
      FirebaseAuthErrorCodes.USER_TOKEN_EXPIRED,
      FirebaseAuthErrorCodes.INVALID_USER_TOKEN -> AuthError.INVALID_CREDENTIALS
      FirebaseAuthErrorCodes.WEAK_PASSWORD -> AuthError.WEAK_PASSWORD
      FirebaseAuthErrorCodes.EMAIL_ALREADY_IN_USE -> AuthError.EMAIL_IN_USE
      FirebaseAuthErrorCodes.NETWORK_REQUEST_FAILED -> AuthError.NETWORK
      FirebaseAuthErrorCodes.TOO_MANY_REQUESTS -> AuthError.TOO_MANY_REQUESTS
      FirebaseAuthErrorCodes.OPERATION_NOT_ALLOWED -> AuthError.PROVIDER_DISABLED
      FirebaseAuthErrorCodes.ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL,
      FirebaseAuthErrorCodes.CREDENTIAL_ALREADY_IN_USE -> AuthError.ACCOUNT_CONFLICT
      else -> AuthError.UNKNOWN
    }

/** Error codes reported by FirebaseAuthException. */
private object FirebaseAuthErrorCodes {
  const val INVALID_EMAIL = "ERROR_INVALID_EMAIL"
  const val WRONG_PASSWORD = "ERROR_WRONG_PASSWORD"
  const val USER_NOT_FOUND = "ERROR_USER_NOT_FOUND"
  const val INVALID_CREDENTIAL = "ERROR_INVALID_CREDENTIAL"
  const val INVALID_LOGIN_CREDENTIALS = "ERROR_INVALID_LOGIN_CREDENTIALS"
  const val USER_DISABLED = "ERROR_USER_DISABLED"
  const val USER_TOKEN_EXPIRED = "ERROR_USER_TOKEN_EXPIRED"
  const val INVALID_USER_TOKEN = "ERROR_INVALID_USER_TOKEN"
  const val WEAK_PASSWORD = "ERROR_WEAK_PASSWORD"
  const val EMAIL_ALREADY_IN_USE = "ERROR_EMAIL_ALREADY_IN_USE"
  const val NETWORK_REQUEST_FAILED = "ERROR_NETWORK_REQUEST_FAILED"
  const val TOO_MANY_REQUESTS = "ERROR_TOO_MANY_REQUESTS"
  const val OPERATION_NOT_ALLOWED = "ERROR_OPERATION_NOT_ALLOWED"
  const val ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL =
      "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL"
  const val CREDENTIAL_ALREADY_IN_USE = "ERROR_CREDENTIAL_ALREADY_IN_USE"
}
