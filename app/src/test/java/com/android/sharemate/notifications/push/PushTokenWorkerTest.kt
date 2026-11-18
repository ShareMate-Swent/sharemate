// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.android.sharemate.model.push.PushTokenRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushTokenWorkerTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val saved = mutableListOf<String>()
  private var failure: Exception? = null

  private val tokens =
      object : PushTokenRepository {
        override suspend fun saveToken(userId: String, deviceId: String, token: String) {
          failure?.let { throw it }
          saved += token
        }

        override suspend fun deleteToken(userId: String, deviceId: String) = Unit
      }

  private fun run(token: String?, user: String? = "alice"): ListenableWorker.Result {
    val factory =
        object : WorkerFactory() {
          override fun createWorker(
              appContext: Context,
              workerClassName: String,
              workerParameters: WorkerParameters,
          ) =
              PushTokenWorker(appContext, workerParameters) {
                PushTokenRegistrar({ user }, tokens, "device-1") { "current-token" }
              }
        }
    val builder =
        TestListenableWorkerBuilder<PushTokenWorker>(context)
            .setWorkerFactory(factory)
            .setInputData(PushTokenRefreshScheduler.inputData(token))
    return runBlocking { builder.build().doWork() }
  }

  @Test
  fun savesTheTokenFromTheInputAndSucceeds() {
    assertEquals(ListenableWorker.Result.success(), run("token-1"))
    assertEquals(listOf("token-1"), saved)
  }

  @Test
  fun savesTheCurrentTokenWhenTheInputHasNone() {
    assertEquals(ListenableWorker.Result.success(), run(null))
    assertEquals(listOf("current-token"), saved)
  }

  @Test
  fun succeedsWithoutSavingWhenNobodyIsSignedIn() {
    assertEquals(ListenableWorker.Result.success(), run("token-1", user = null))
    assertEquals(emptyList<String>(), saved)
  }

  @Test
  fun retriesWhenTheSaveFails() {
    failure = IllegalStateException("offline")

    assertEquals(ListenableWorker.Result.retry(), run("token-1"))
  }
}
