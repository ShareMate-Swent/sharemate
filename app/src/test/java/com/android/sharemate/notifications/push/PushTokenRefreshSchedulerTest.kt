// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushTokenRefreshSchedulerTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Before
  fun initializeWorkManager() {
    WorkManagerTestInitHelper.initializeTestWorkManager(context)
  }

  private fun scheduled() =
      WorkManager.getInstance(context)
          .getWorkInfosForUniqueWork(PushTokenRefreshScheduler.WORK_NAME)
          .get()

  @Test
  fun enqueuesOneJobThatWaitsForTheNetwork() {
    PushTokenRefreshScheduler.enqueue(context, "token-1")

    val job = scheduled().single()
    assertEquals(WorkInfo.State.ENQUEUED, job.state)
    assertEquals(NetworkType.CONNECTED, job.constraints.requiredNetworkType)
  }

  @Test
  fun aNewerRequestReplacesTheOneStillWaiting() {
    PushTokenRefreshScheduler.enqueue(context, "token-1")
    PushTokenRefreshScheduler.enqueue(context, null)

    assertEquals(listOf(WorkInfo.State.ENQUEUED), scheduled().map { it.state })
  }

  @Test
  fun theInputCarriesTheTokenWhenOneIsKnown() {
    val data = PushTokenRefreshScheduler.inputData("token-1")

    assertEquals("token-1", data.getString(PushTokenRefreshScheduler.KEY_TOKEN))
  }

  @Test
  fun theInputIsEmptyWhenTheWorkerMustReadTheCurrentToken() {
    assertNull(
        PushTokenRefreshScheduler.inputData(null).getString(PushTokenRefreshScheduler.KEY_TOKEN))
  }
}
