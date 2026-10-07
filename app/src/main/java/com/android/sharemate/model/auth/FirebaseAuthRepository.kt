package com.android.sharemate.model.auth

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await

class FirebaseAuthRepository(private val auth: FirebaseAuth) : AuthRepository {
  override val session: Flow<AuthSession?> =
      callbackFlow {
            val listener =
                FirebaseAuth.AuthStateListener { firebaseAuth ->
                  trySend(firebaseAuth.currentUser?.let { AuthSession(it.uid, it.email) })
                }
            auth.addAuthStateListener(listener)
            awaitClose { auth.removeAuthStateListener(listener) }
          }
          .distinctUntilChanged()

  override suspend fun signIn(email: String, password: String) {
    authenticate { auth.signInWithEmailAndPassword(email, password).await() }
  }

  override suspend fun signUp(email: String, password: String) {
    authenticate { auth.createUserWithEmailAndPassword(email, password).await() }
  }

  override fun signOut() = auth.signOut()

  private suspend fun authenticate(action: suspend () -> Unit) {
    try {
      action()
    } catch (cancelled: CancellationException) {
      throw cancelled
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
}

internal fun authErrorForCode(code: String): AuthError =
    when (code) {
      "ERROR_INVALID_EMAIL" -> AuthError.INVALID_EMAIL
      "ERROR_WRONG_PASSWORD",
      "ERROR_USER_NOT_FOUND",
      "ERROR_INVALID_CREDENTIAL",
      "ERROR_INVALID_LOGIN_CREDENTIALS",
      "ERROR_USER_DISABLED",
      "ERROR_USER_TOKEN_EXPIRED",
      "ERROR_INVALID_USER_TOKEN" -> AuthError.INVALID_CREDENTIALS
      "ERROR_WEAK_PASSWORD" -> AuthError.WEAK_PASSWORD
      "ERROR_EMAIL_ALREADY_IN_USE" -> AuthError.EMAIL_IN_USE
      "ERROR_NETWORK_REQUEST_FAILED" -> AuthError.NETWORK
      "ERROR_TOO_MANY_REQUESTS" -> AuthError.TOO_MANY_REQUESTS
      "ERROR_OPERATION_NOT_ALLOWED" -> AuthError.PROVIDER_DISABLED
      else -> AuthError.UNKNOWN
    }
