// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

/**
 * Hands the registration of this device's push token to [PushTokenWorker]. FCM stops its service
 * soon after `onNewToken` returns, and the first read of a token needs a network, so the save runs
 * in WorkManager, which survives both and retries.
 */
object PushTokenRefreshScheduler {
  /** Unique name of the registration job: only one runs or waits at a time. */
  const val WORK_NAME = "push_token_refresh"

  /** Input key of the token FCM just issued; absent when the worker must read the current one. */
  const val KEY_TOKEN = "token"

  /**
   * Schedules a registration. [token] is the one FCM just issued, or null to register whatever the
   * current token is. A newer request replaces one still waiting: both end up saving the latest
   * token, so nothing is done twice and a stale token never overwrites a fresher one.
   */
  fun enqueue(context: Context, token: String?) {
    val request =
        OneTimeWorkRequestBuilder<PushTokenWorker>()
            .setInputData(inputData(token))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
    WorkManager.getInstance(context)
        .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
  }

  /** The job's input: the token when one is known, nothing otherwise. */
  internal fun inputData(token: String?): Data =
      if (token == null) Data.EMPTY else workDataOf(KEY_TOKEN to token)
}
