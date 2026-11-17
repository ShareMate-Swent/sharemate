// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
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
  fun aNewerTokenReplacesTheOneStillWaiting() {
    PushTokenRefreshScheduler.enqueue(context, "token-1")
    PushTokenRefreshScheduler.enqueue(context, "token-2")

    val states = scheduled().map { it.state }
    assertEquals(1, states.count { it == WorkInfo.State.ENQUEUED })
    assertEquals(0, states.count { it == WorkInfo.State.RUNNING })
  }
}
