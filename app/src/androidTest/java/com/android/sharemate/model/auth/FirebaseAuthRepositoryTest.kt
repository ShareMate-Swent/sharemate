// Co-authored-by: Codex
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.auth

import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only the demo project's local Auth emulator; run adb reverse tcp:9099 tcp:9099 first. */
@RunWith(AndroidJUnit4::class)
class FirebaseAuthRepositoryTest {
  private lateinit var app: FirebaseApp
  private lateinit var auth: FirebaseAuth
  private lateinit var repository: FirebaseAuthRepository

  @Before
  fun setUp() {
    val options =
        FirebaseOptions.Builder()
            .setProjectId("demo-sharemate")
            .setApplicationId("1:123456789:android:auth-test")
            .setApiKey("demo-api-key")
            .build()
    app =
        FirebaseApp.initializeApp(
            getApplicationContext(), options, "auth-test-${UUID.randomUUID()}")
    auth = FirebaseAuth.getInstance(app)
    auth.useEmulator("127.0.0.1", 9099)
    auth.signOut()
    repository = FirebaseAuthRepository(auth)
  }

  @After
  fun tearDown() = runBlocking {
    auth.currentUser?.delete()?.await()
    auth.signOut()
    app.delete()
  }

  @Test
  fun signupSignoutLoginAndNewRepositoryRestoreTheSameSession() = runBlocking {
    withTimeout(20000) {
      assertNull(repository.session.first())
      val email = "student-${UUID.randomUUID()}@example.org"
      val password = "test-password"
      repository.signUp(email, password)
      val signedUp = repository.session.first { it != null }!!
      assertEquals(email, signedUp.email)
      assertEquals(SignInProvider.EMAIL, signedUp.provider)
      val logout = async { repository.session.first { it == null } }
      repository.signOut()
      assertNull(logout.await())
      repository.signIn(email, password)
      assertEquals(signedUp.uid, repository.session.first { it != null }?.uid)
      // Recreate the Firebase application itself, so restoration reads SDK persistence,
      // rather than merely reusing the same FirebaseAuth instance's currentUser.
      val options = app.options
      val name = app.name
      app.delete()
      app = FirebaseApp.initializeApp(getApplicationContext(), options, name)
      auth = FirebaseAuth.getInstance(app)
      auth.useEmulator("127.0.0.1", 9099)
      val recreated = FirebaseAuthRepository(auth)
      assertEquals(signedUp, recreated.session.first())
    }
  }

  @Test
  fun wrongPasswordReturnsSafeFailureAndDoesNotCreateSession() = runBlocking {
    withTimeout(20000) {
      val email = "student-${UUID.randomUUID()}@example.org"
      repository.signUp(email, "test-password")
      repository.signOut()
      try {
        repository.signIn(email, "wrong-password")
        fail("Wrong password must be rejected")
      } catch (failure: AuthException) {
        assertEquals(AuthError.INVALID_CREDENTIALS, failure.error)
        assertEquals("INVALID_CREDENTIALS", failure.message)
      }
      assertNull(repository.session.first())
      // Restore the test user so tearDown deletes it from the emulator.
      repository.signIn(email, "test-password")
    }
  }

  @Test
  fun googleCredentialEstablishesFirebaseSessionAndSignsOut() = runBlocking {
    withTimeout(20000) {
      val id = UUID.randomUUID().toString()
      val email = "google-$id@example.org"
      // Literal JSON ID tokens are accepted only by the local Auth emulator.
      repository.signInWithGoogle("""{"sub":"$id","email":"$email","email_verified":true}""")
      val session = repository.session.first { it != null }!!
      assertEquals(email, session.email)
      assertEquals(auth.currentUser?.uid, session.uid)
      assertTrue(auth.currentUser!!.providerData.any { it.providerId == "google.com" })
      repository.signOut()
      assertNull(repository.session.first())
      // Reauthenticate solely to let the existing fixture delete the emulator user.
      repository.signInWithGoogle("""{"sub":"$id","email":"$email","email_verified":true}""")
      assertEquals(session.uid, repository.session.first { it != null }?.uid)
    }
  }

  @Test
  fun duplicateSignupReturnsEmailInUse() = runBlocking {
    withTimeout(20000) {
      val email = "student-${UUID.randomUUID()}@example.org"
      repository.signUp(email, "test-password")
      try {
        repository.signUp(email, "test-password")
        fail("Duplicate account must be rejected")
      } catch (failure: AuthException) {
        assertEquals(AuthError.EMAIL_IN_USE, failure.error)
      }
    }
  }
}
