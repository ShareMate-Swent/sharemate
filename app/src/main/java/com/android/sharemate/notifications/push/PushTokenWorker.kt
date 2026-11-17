// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Background entry point: saves the token given by [PushTokenRefreshScheduler], retrying on
 * failure.
 */
class PushTokenWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val token = inputData.getString(PushTokenRefreshScheduler.KEY_TOKEN) ?: return Result.failure()
    val saved = PushTokenRegistrarFactory.create(applicationContext).onTokenRefreshed(token)
    return if (saved) Result.success() else Result.retry()
  }
}
