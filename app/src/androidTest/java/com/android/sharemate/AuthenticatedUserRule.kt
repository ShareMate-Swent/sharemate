package com.android.sharemate

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.rules.ExternalResource

/** Prepares a local Auth emulator session before launching the activity. */
class AuthenticatedUserRule(private val signedIn: Boolean = true) : ExternalResource() {
  override fun before() {
    auth.signOut()
    if (signedIn) {
      await(
          auth.createUserWithEmailAndPassword(
              "navigation-${UUID.randomUUID()}@example.org", "test-secret"))
    }
  }

  override fun after() {
    try {
      auth.currentUser?.let { await(it.delete()) }
    } finally {
      auth.signOut()
    }
  }

  private fun <T> await(task: Task<T>): T = Tasks.await(task, 20, TimeUnit.SECONDS)

  companion object {
    // Keep one FirebaseApp: recreating it leaves duplicate SDK DataStores in the test process.
    private val auth by lazy { FirebaseAuth.getInstance().apply { useEmulator("127.0.0.1", 9099) } }
  }
}
