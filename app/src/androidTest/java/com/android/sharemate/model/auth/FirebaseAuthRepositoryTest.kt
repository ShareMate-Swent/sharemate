// Co-authored-by: Codex
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.auth

import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.utils.FirebaseEmulator
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
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

/** Uses only the demo project's local Auth and Firestore emulators. */
@RunWith(AndroidJUnit4::class)
class FirebaseAuthRepositoryTest {
  private lateinit var app: FirebaseApp
  private lateinit var auth: FirebaseAuth
  private lateinit var firestore: FirebaseFirestore
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
    auth.useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.AUTH_PORT)
    firestore = FirebaseFirestore.getInstance(app)
    firestore.useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.FIRESTORE_PORT)
    auth.signOut()
    repository = FirebaseAuthRepository(auth, FirestorePseudoRepository(firestore))
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
      val pseudo = "New User ${UUID.randomUUID()}"
      repository.signUp(email, password, pseudo)
      val signedUp = repository.session.first { it != null }!!
      assertEquals(email, signedUp.email)
      assertEquals(pseudo, signedUp.displayName)
      assertEquals(SignInProvider.EMAIL, signedUp.provider)
      assertEquals(pseudo, auth.currentUser?.displayName)
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
      auth.useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.AUTH_PORT)
      firestore = FirebaseFirestore.getInstance(app)
      firestore.useEmulator(FirebaseEmulator.HOST, FirebaseEmulator.FIRESTORE_PORT)
      val recreated = FirebaseAuthRepository(auth, FirestorePseudoRepository(firestore))
      assertEquals(signedUp, recreated.session.first())
    }
  }

  @Test
  fun wrongPasswordReturnsSafeFailureAndDoesNotCreateSession() = runBlocking {
    withTimeout(20000) {
      val email = "student-${UUID.randomUUID()}@example.org"
      repository.signUp(email, "test-password", "New User ${UUID.randomUUID()}")
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
  fun signupRejectsCaseAndWhitespaceInsensitiveDuplicatePseudos() = runBlocking {
    withTimeout(20000) {
      val pseudo = "Alex ${UUID.randomUUID()}"
      repository.signUp("first-${UUID.randomUUID()}@example.org", "test-password", pseudo)
      repository.signOut()

      try {
        repository.signUp(
            "second-${UUID.randomUUID()}@example.org", "test-password", "  ${pseudo.uppercase()}  ")
        fail("A normalized duplicate pseudo must be rejected")
      } catch (failure: AuthException) {
        assertEquals(AuthError.PSEUDO_TAKEN, failure.error)
      }
      assertEquals("alex", normalizePseudo("  Alex  "))
      assertNull(auth.currentUser?.displayName)
    }
  }

  @Test
  fun profileUpdateRetryPersistsNameOnExistingAccount() = runBlocking {
    withTimeout(20000) {
      val email = "student-${UUID.randomUUID()}@example.org"
      val password = "test-password"
      val pseudo = "Recovered Name ${UUID.randomUUID()}"
      val created = auth.createUserWithEmailAndPassword(email, password).await().user!!
      assertNull(created.displayName)

      repository.updateCurrentUserDisplayName(created.uid, pseudo)

      assertEquals(created.uid, auth.currentUser?.uid)
      assertEquals(pseudo, auth.currentUser?.displayName)
      assertEquals(pseudo, repository.session.first { it != null }?.displayName)
      repository.signOut()
      repository.signIn(email, password)
      assertEquals(created.uid, repository.session.first { it != null }?.uid)
      assertEquals(pseudo, repository.session.first { it != null }?.displayName)
    }
  }

  @Test
  fun googleCredentialEstablishesFirebaseSessionAndSignsOut() = runBlocking {
    withTimeout(20000) {
      val id = UUID.randomUUID().toString()
      val email = "google-$id@example.org"
      // Literal JSON ID tokens are accepted only by the local Auth emulator.
      repository.signInWithGoogle(
          """{"sub":"$id","email":"$email","email_verified":true,"name":"Google User"}""")
      val session = repository.session.first { it != null }!!
      assertEquals(email, session.email)
      assertEquals("Google User", session.displayName)
      assertEquals(auth.currentUser?.uid, session.uid)
      assertTrue(auth.currentUser!!.providerData.any { it.providerId == "google.com" })
      repository.signOut()
      assertNull(repository.session.first())
      // Reauthenticate solely to let the existing fixture delete the emulator user.
      repository.signInWithGoogle(
          """{"sub":"$id","email":"$email","email_verified":true,"name":"Google User"}""")
      assertEquals(session.uid, repository.session.first { it != null }?.uid)
    }
  }

  @Test
  fun duplicateSignupReturnsEmailInUse() = runBlocking {
    withTimeout(20000) {
      val email = "student-${UUID.randomUUID()}@example.org"
      repository.signUp(email, "test-password", "New User ${UUID.randomUUID()}")
      try {
        repository.signUp(email, "test-password", "New User ${UUID.randomUUID()}")
        fail("Duplicate account must be rejected")
      } catch (failure: AuthException) {
        assertEquals(AuthError.EMAIL_IN_USE, failure.error)
      }
    }
  }
}
