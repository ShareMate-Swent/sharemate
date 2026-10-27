// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Runs [NotificationWorker] once a day, in the morning. */
object NotificationScheduler {
  const val WORK_NAME = "notifications_daily_check"
  const val DAILY_CHECK_HOUR = 9

  /** Idempotent: an already scheduled check is kept as is. */
  fun schedule(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
    val request =
        PeriodicWorkRequestBuilder<NotificationWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelay(now))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
    WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
  }

  /** Time from [now] until the next [DAILY_CHECK_HOUR]:00 in the device's time zone. */
  fun initialDelay(now: ZonedDateTime): Duration {
    var next = now.toLocalDate().atTime(DAILY_CHECK_HOUR, 0).atZone(now.zone)
    if (!next.isAfter(now))
        next = now.toLocalDate().plusDays(1).atTime(DAILY_CHECK_HOUR, 0).atZone(now.zone)
    return Duration.between(now, next)
  }
}
