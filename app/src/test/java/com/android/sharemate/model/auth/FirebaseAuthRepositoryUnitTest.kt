package com.android.sharemate.model.auth

import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
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
  fun signOutDelegatesToFirebase() {
    repository.signOut()
    verify(auth).signOut()
  }
}
