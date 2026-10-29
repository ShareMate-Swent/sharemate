// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Background entry point: runs every notification source and retries if one of them failed. */
class NotificationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val result = NotificationDispatcherFactory.create(applicationContext).dispatch()
    return if (result.isSuccess) Result.success() else Result.retry()
  }
}
