// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: OpenAI Codex
package com.android.sharemate.utils

import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.firestore.FirebaseFirestore
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutionException

/**
 * Gives instrumented tests access to the local Firebase emulators.
 *
 * The Firestore emulator enforces `firestore.rules`, so tests must act as a signed-in user and can
 * only reset data through the emulator's administrative REST endpoints.
 */
object FirebaseEmulator {
  const val HOST = "10.0.2.2"
  const val FIRESTORE_PORT = 8080
  const val AUTH_PORT = 9099
  private const val TEST_PASSWORD = "emulator-only-password"

  val firestore: FirebaseFirestore by lazy {
    FirebaseFirestore.getInstance().apply { useEmulator(HOST, FIRESTORE_PORT) }
  }

  val auth: FirebaseAuth by lazy {
    FirebaseAuth.getInstance().apply { useEmulator(HOST, AUTH_PORT) }
  }

  private val projectId: String
    get() = checkNotNull(FirebaseApp.getInstance().options.projectId)

  /**
   * Signs in as the emulator account named [alias], creating it on first use.
   *
   * @return The user identifier of the signed-in account.
   */
  fun signInAs(alias: String): String {
    val email = "$alias@example.com"
    val result =
        try {
          Tasks.await(auth.createUserWithEmailAndPassword(email, TEST_PASSWORD))
        } catch (e: ExecutionException) {
          if (e.cause !is FirebaseAuthUserCollisionException) throw e
          Tasks.await(auth.signInWithEmailAndPassword(email, TEST_PASSWORD))
        }
    awaitUserSwitch()
    return checkNotNull(result.user).uid
  }

  fun signOut() {
    auth.signOut()
    awaitUserSwitch()
  }

  /**
   * Lets Firestore see the new user before the test writes anything.
   *
   * Firebase Auth tells Firestore about a user change on the main thread. A write issued before
   * that is filed under the previous user and never sent, so its confirmation never arrives (#285).
   * Draining the main thread delivers the change first; Firestore then handles it before any later
   * write.
   */
  private fun awaitUserSwitch() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
  }

  /** Signs out and deletes every account and document stored in the emulators. */
  fun clear() {
    auth
    firestore
    signOut()
    delete("http://$HOST:$AUTH_PORT/emulator/v1/projects/$projectId/accounts")
    delete(
        "http://$HOST:$FIRESTORE_PORT/emulator/v1/projects/$projectId/databases/(default)/documents")
  }

  private fun delete(url: String) {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
      connection.requestMethod = "DELETE"
      connection.setRequestProperty("Authorization", "Bearer owner")
      check(connection.responseCode in 200..299) {
        "Could not clear emulator data at $url: HTTP ${connection.responseCode}"
      }
    } finally {
      connection.disconnect()
    }
  }
}
