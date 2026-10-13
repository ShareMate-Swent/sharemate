package com.android.sharemate

import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.utils.FirebaseEmulator
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.rules.ExternalResource

/** Prepares a local Auth emulator session before launching the activity. */
class AuthenticatedUserRule(private val signedIn: Boolean = true) : ExternalResource() {
  private val auth
    get() = FirebaseEmulator.auth

  override fun before() {
    try {
      val firestore = FirebaseEmulator.firestore
      auth.signOut()
      if (signedIn) {
        await(
            auth.createUserWithEmailAndPassword(
                "navigation-${UUID.randomUUID()}@example.org", "test-secret"))
        runBlocking {
          withTimeout(20_000) {
            HouseholdRepositoryFirestore(firestore)
                .createHousehold("Test household", checkNotNull(auth.currentUser).uid)
          }
        }
      }
    } catch (failure: Throwable) {
      runCatching { after() }.exceptionOrNull()?.let(failure::addSuppressed)
      throw failure
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
}
