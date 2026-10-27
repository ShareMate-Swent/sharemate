// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationSchedulerWorkTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Before
  fun initializeWorkManager() {
    WorkManagerTestInitHelper.initializeTestWorkManager(context)
  }

  private fun scheduled() =
      WorkManager.getInstance(context)
          .getWorkInfosForUniqueWork(NotificationScheduler.WORK_NAME)
          .get()

  @Test
  fun schedulesOneDailyCheckEvenWhenCalledRepeatedly() {
    NotificationScheduler.schedule(context)
    NotificationScheduler.schedule(context)

    assertEquals(NetworkType.CONNECTED, scheduled().single().constraints.requiredNetworkType)
  }
}
