// Co-authored-by: Codex
package com.android.sharemate.ui.auth

import android.annotation.SuppressLint
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.android.sharemate.model.auth.AuthError
import com.android.sharemate.model.auth.AuthException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CancellationException

/** Android account picker adapter. Its retained fields contain only application context. */
class CredentialManagerGoogleSignIn(
    context: Context,
    credentialManager: CredentialManager? = null,
    private val serverClientId: String? = null,
) {
  private val applicationContext = context.applicationContext
  private val manager by lazy { credentialManager ?: CredentialManager.create(applicationContext) }

  /** The Activity context is used only for this UI-scoped request, never retained in a field. */
  // OAuth setup generates this optional resource; lookup keeps email auth usable before setup.
  @SuppressLint("DiscouragedApi")
  suspend fun getIdToken(context: Context): String? {
    val resource =
        applicationContext.resources.getIdentifier(
            "default_web_client_id", "string", applicationContext.packageName)
    val clientId =
        serverClientId ?: if (resource == 0) "" else applicationContext.getString(resource)
    if (clientId.isBlank()) throw AuthException(AuthError.GOOGLE_UNAVAILABLE)
    val option = GetSignInWithGoogleOption.Builder(clientId).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    try {
      val credential = manager.getCredential(context, request).credential
      if (credential !is CustomCredential ||
          credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        throw AuthException(AuthError.GOOGLE_UNAVAILABLE)
      }
      return GoogleIdTokenCredential.createFrom(credential.data).idToken
    } catch (_: GetCredentialCancellationException) {
      return null
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: GetCredentialException) {
      throw AuthException(AuthError.GOOGLE_UNAVAILABLE)
    } catch (_: Exception) {
      // Malformed credentials and platform failures never expose provider messages or token data.
      throw AuthException(AuthError.GOOGLE_UNAVAILABLE)
    }
  }

  suspend fun clearSession() {
    manager.clearCredentialState(ClearCredentialStateRequest())
  }
}
