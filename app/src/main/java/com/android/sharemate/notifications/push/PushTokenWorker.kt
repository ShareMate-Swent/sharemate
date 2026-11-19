// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Background entry point: registers the token given by [PushTokenRefreshScheduler] (or the current
 * one), retrying on failure. The registrar is built through [registrarProvider], so tests can
 * replace it.
 */
class PushTokenWorker(
    context: Context,
    params: WorkerParameters,
    private val registrarProvider: (Context) -> PushTokenRegistrar,
) : CoroutineWorker(context, params) {

  /** Constructor WorkManager's default factory calls: wires the production Firebase services. */
  constructor(
      context: Context,
      params: WorkerParameters,
  ) : this(context, params, ::productionRegistrar)

  override suspend fun doWork(): Result {
    val token = inputData.getString(PushTokenRefreshScheduler.KEY_TOKEN)
    val registered = registrarProvider(applicationContext).register(token)
    return if (registered) Result.success() else Result.retry()
  }
}

private fun productionRegistrar(context: Context): PushTokenRegistrar =
    PushTokenRegistrarFactory.create(
        DeviceId.getOrCreate(context),
        FirebaseAuth.getInstance(),
        FirebaseFirestore.getInstance(),
        lazy { FirebaseMessaging.getInstance() })
