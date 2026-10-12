// Co-authored-by: Codex
package com.android.sharemate.ui.auth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.credentials.*
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.android.sharemate.model.auth.AuthError
import com.android.sharemate.model.auth.AuthException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CredentialManagerGoogleSignInTest {
  private val context = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
  private val manager = mock(CredentialManager::class.java)
  private val fallbackRequest =
      GetCredentialRequest.Builder()
          .addCredentialOption(GetSignInWithGoogleOption.Builder("test-client").build())
          .build()

  private fun anyRequest() = any(GetCredentialRequest::class.java) ?: fallbackRequest

  @Test
  fun missingConfigurationNeverOpensThePicker() = runBlocking {
    val adapter = CredentialManagerGoogleSignIn(context, manager, "")
    assertUnavailable { adapter.getIdToken(context) }
    verifyNoInteractions(manager)
  }

  @Test
  fun googleCredentialUsesActivityContextAndConfiguredClientAndClearsProviderState() = runBlocking {
    val credential =
        GoogleIdTokenCredential.Builder()
            .setId("student@example.org")
            .setIdToken("exact-token")
            .build()
    var request: GetCredentialRequest? = null
    doAnswer {
          request = it.getArgument(1)
          GetCredentialResponse(CustomCredential(credential.type, credential.data))
        }
        .`when`(manager)
        .getCredential(eq(context) ?: context, anyRequest())
    val adapter = CredentialManagerGoogleSignIn(context, manager, "web-client-id")
    assertEquals("exact-token", adapter.getIdToken(context))
    val option = request!!.credentialOptions.single() as GetSignInWithGoogleOption
    assertEquals("web-client-id", option.serverClientId)
    adapter.clearSession()
    verify(manager).getCredential(eq(context) ?: context, anyRequest())
    verify(manager)
        .clearCredentialState(
            any(ClearCredentialStateRequest::class.java) ?: ClearCredentialStateRequest())
  }

  @Test
  fun wrongCredentialAndMalformedGoogleDataProduceOnlySafeErrors() = runBlocking {
    val adapter = CredentialManagerGoogleSignIn(context, manager, "web-client-id")
    for (credential in
        listOf(
            CustomCredential("unexpected-provider", Bundle()),
            CustomCredential(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, Bundle()))) {
      doReturn(GetCredentialResponse(credential))
          .`when`(manager)
          .getCredential(eq(context) ?: context, anyRequest())
      assertUnavailable { adapter.getIdToken(context) }
    }
  }

  @Test
  fun pickerDismissalIsSilentButCoroutineCancellationPropagates() = runBlocking {
    val adapter = CredentialManagerGoogleSignIn(context, manager, "web-client-id")
    doAnswer { throw GetCredentialCancellationException("private response") }
        .`when`(manager)
        .getCredential(eq(context) ?: context, anyRequest())
    assertNull(adapter.getIdToken(context))
    doAnswer { throw NoCredentialException("private response") }
        .`when`(manager)
        .getCredential(eq(context) ?: context, anyRequest())
    assertUnavailable { adapter.getIdToken(context) }
    doAnswer { throw CancellationException("scope cancelled") }
        .`when`(manager)
        .getCredential(eq(context) ?: context, anyRequest())
    try {
      adapter.getIdToken(context)
      fail("Scope cancellation must propagate")
    } catch (_: CancellationException) {}
  }

  private suspend fun assertUnavailable(action: suspend () -> Unit) {
    try {
      action()
      fail("Invalid picker result must be rejected")
    } catch (failure: AuthException) {
      assertEquals(AuthError.GOOGLE_UNAVAILABLE, failure.error)
      assertEquals("GOOGLE_UNAVAILABLE", failure.message)
      assertNull(failure.cause)
    }
  }
}
