// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushMessagingServiceTest {
  @Test
  fun aNewTokenSchedulesTheRegistrationJob() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    WorkManagerTestInitHelper.initializeTestWorkManager(context)

    Robolectric.setupService(PushMessagingService::class.java).onNewToken("token-1")

    val jobs =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(PushTokenRefreshScheduler.WORK_NAME)
            .get()
    assertEquals(WorkInfo.State.ENQUEUED, jobs.single().state)
  }
}
