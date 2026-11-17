// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

/**
 * Hands a refreshed push token to [PushTokenWorker]. FCM stops its service soon after `onNewToken`
 * returns, so the save runs in WorkManager, which survives that and retries.
 */
object PushTokenRefreshScheduler {
  const val WORK_NAME = "push_token_refresh"
  const val KEY_TOKEN = "token"

  /** A newer token replaces one that is still waiting: only the latest is worth saving. */
  fun enqueue(context: Context, token: String) {
    val request =
        OneTimeWorkRequestBuilder<PushTokenWorker>()
            .setInputData(workDataOf(KEY_TOKEN to token))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
    WorkManager.getInstance(context)
        .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
  }
}
