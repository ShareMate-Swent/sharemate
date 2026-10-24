// Co-authored-by: Codex
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.auth

import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class FirebaseAuthRepositoryUnitTest {
  private val auth = mock(FirebaseAuth::class.java)
  private val repository = FirebaseAuthRepository(auth)

  @Test
  fun forwardsCredentialsForLoginAndSignup() =
      runBlocking<Unit> {
        val result = Tasks.forResult(mock(AuthResult::class.java))
        `when`(auth.signInWithEmailAndPassword("student@example.org", " secret "))
            .thenReturn(result)
        `when`(auth.createUserWithEmailAndPassword("new@example.org", "new-secret"))
            .thenReturn(result)
        repository.signIn("student@example.org", " secret ")
        repository.signUp("new@example.org", "new-secret")
        verify(auth).signInWithEmailAndPassword("student@example.org", " secret ")
        verify(auth).createUserWithEmailAndPassword("new@example.org", "new-secret")
      }

  @Test
  fun mapsFailedTasksWithoutExposingProviderMessages() =
      runBlocking<Unit> {
        val failures =
            listOf(
                FirebaseNetworkException("private response") to AuthError.NETWORK,
                FirebaseTooManyRequestsException("private response") to AuthError.TOO_MANY_REQUESTS,
                FirebaseAuthException("ERROR_WRONG_PASSWORD", "private response") to
                    AuthError.INVALID_CREDENTIALS,
                IllegalStateException("private response") to AuthError.UNKNOWN)
        for ((failure, expected) in failures) {
          `when`(auth.signInWithEmailAndPassword("student@example.org", "secret"))
              .thenReturn(Tasks.forException(failure))
          try {
            repository.signIn("student@example.org", "secret")
            fail("Failed task must become a domain failure")
          } catch (actual: AuthException) {
            assertEquals(expected, actual.error)
            assertEquals(expected.name, actual.message)
            assertNull(actual.cause)
          }
        }
      }

  @Test
  fun cancelledTaskPropagatesCancellation() =
      runBlocking<Unit> {
        `when`(auth.createUserWithEmailAndPassword("new@example.org", "secret"))
            .thenReturn(Tasks.forCanceled())
        try {
          repository.signUp("new@example.org", "secret")
          fail("Cancellation must propagate")
        } catch (expected: CancellationException) {
          assertEquals(CancellationException::class.java, expected.javaClass)
        }
      }

  @Test
  fun sessionEmitsDistinctIdentityAndRemovesListenerAfterCollection() =
      runBlocking<Unit> {
        val user = mock(FirebaseUser::class.java)
        `when`(user.uid).thenReturn("trusted-uid")
        `when`(user.email).thenReturn("student@example.org")
        `when`(auth.currentUser).thenReturn(null, null, user)
        lateinit var listener: FirebaseAuth.AuthStateListener
        doAnswer {
              listener = it.getArgument(0)
              repeat(3) { listener.onAuthStateChanged(auth) }
              null
            }
            .`when`(auth)
            .addAuthStateListener(any(FirebaseAuth.AuthStateListener::class.java))
        assertEquals(
            listOf(null, AuthSession("trusted-uid", "student@example.org")),
            repository.session.take(2).toList())
        verify(auth).removeAuthStateListener(listener)
      }

  @Test
  fun googleExchangesTheExactTokenAndReturnsSafeFailures() =
      runBlocking<Unit> {
        val credential = mock(com.google.firebase.auth.AuthCredential::class.java)
        mockStatic(com.google.firebase.auth.GoogleAuthProvider::class.java).use { provider ->
          provider
              .`when`<com.google.firebase.auth.AuthCredential> {
                com.google.firebase.auth.GoogleAuthProvider.getCredential(" exact-token ", null)
              }
              .thenReturn(credential)
          `when`(auth.signInWithCredential(credential))
              .thenReturn(Tasks.forResult(mock(AuthResult::class.java)))
          repository.signInWithGoogle(" exact-token ")
          provider.verify {
            com.google.firebase.auth.GoogleAuthProvider.getCredential(" exact-token ", null)
          }
          verify(auth).signInWithCredential(credential)
          for ((code, expected) in
              listOf(
                  "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" to AuthError.ACCOUNT_CONFLICT,
                  "ERROR_OPERATION_NOT_ALLOWED" to AuthError.PROVIDER_DISABLED)) {
            `when`(auth.signInWithCredential(credential))
                .thenReturn(Tasks.forException(FirebaseAuthException(code, "private response")))
            try {
              repository.signInWithGoogle(" exact-token ")
              fail("Failed Google exchange must be mapped")
            } catch (actual: AuthException) {
              assertEquals(expected, actual.error)
              assertEquals(expected.name, actual.message)
              assertNull(actual.cause)
            }
          }
          `when`(auth.signInWithCredential(credential)).thenReturn(Tasks.forCanceled())
          try {
            repository.signInWithGoogle(" exact-token ")
            fail("Google task cancellation must propagate")
          } catch (_: CancellationException) {}
        }
      }

  @Test
  fun googleUserExposesProfileAndGoogleProvider() {
    val photo = mock(Uri::class.java)
    `when`(photo.toString()).thenReturn("https://example.org/photo.png")
    val user = user(providers = listOf("firebase", "google.com"), name = "Inge", photo = photo)

    assertEquals(
        AuthSession(
            uid = "uid",
            email = "student@example.org",
            displayName = "Inge",
            photoUrl = "https://example.org/photo.png",
            provider = SignInProvider.GOOGLE),
        user.toAuthSession())
  }

  @Test
  fun emailUserHasEmailProviderAndNoProfile() {
    assertEquals(
        AuthSession("uid", "student@example.org", provider = SignInProvider.EMAIL),
        user(providers = listOf("firebase", "password")).toAuthSession())
  }

  @Test
  fun blankProfileValuesBecomeMissing() {
    val photo = mock(Uri::class.java)
    `when`(photo.toString()).thenReturn("")
    val session = user(providers = listOf("google.com"), name = "  ", photo = photo).toAuthSession()

    assertNull(session.displayName)
    assertNull(session.photoUrl)
  }

  @Test
  fun googleWinsWhenBothProvidersAreLinkedAndOthersAreUnknown() {
    assertEquals(
        SignInProvider.GOOGLE,
        user(providers = listOf("password", "google.com")).toAuthSession().provider)
    assertEquals(
        SignInProvider.UNKNOWN, user(providers = listOf("firebase")).toAuthSession().provider)
    assertEquals(SignInProvider.UNKNOWN, user(providers = emptyList()).toAuthSession().provider)
  }

  private fun user(providers: List<String>, name: String? = null, photo: Uri? = null) =
      mock(FirebaseUser::class.java).also { user ->
        val infos =
            providers.map { id ->
              mock(UserInfo::class.java).also { `when`(it.providerId).thenReturn(id) }
            }
        `when`(user.uid).thenReturn("uid")
        `when`(user.email).thenReturn("student@example.org")
        `when`(user.displayName).thenReturn(name)
        `when`(user.photoUrl).thenReturn(photo)
        doReturn(infos).`when`(user).providerData
      }

  @Test
  fun signOutDelegatesToFirebase() {
    repository.signOut()
    verify(auth).signOut()
  }
}
