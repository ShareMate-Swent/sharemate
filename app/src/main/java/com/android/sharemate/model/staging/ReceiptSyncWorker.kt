// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.staging

import android.content.Context
import androidx.work.*
import com.android.sharemate.model.finance.FinanceCallablePolicy
import com.android.sharemate.model.finance.FirebaseExpenseSubmissionRepository
import com.android.sharemate.model.item.*
import com.android.sharemate.model.receipt.RECEIPT_SYNC_TAG
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

/** Durable network-constrained continuation of one already journaled purchase. */
class ReceiptSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val currentUserId: () -> String?,
    private val synchronize: suspend (String, String, String, String, Boolean) -> Boolean,
) : CoroutineWorker(context, params) {
  /**
   * Standard WorkManager entry point; tests can inject the session and synchronization boundary.
   */
  constructor(
      context: Context,
      params: WorkerParameters
  ) : this(
      context,
      params,
      { FirebaseAuth.getInstance().currentUser?.uid },
      { owner, home, id, actor, delete ->
        val store =
            AndroidInventorySubmissionStore(
                context.applicationContext, scheduleSync = false, viewerId = actor)
        val items = FirebaseItemRepository(FirebaseFirestore.getInstance())
        val expenses =
            FirebaseExpenseSubmissionRepository(
                FirebaseFunctions.getInstance(FinanceCallablePolicy.REGION))
        ReceiptSynchronization(store, items, expenses) {
              upload(context.applicationContext, store, it)
            }
            .synchronize(owner, home, id, actor, delete)
      })

  override suspend fun doWork(): Result {
    val owner = inputData.getString("owner") ?: return Result.failure()
    val home = inputData.getString("home") ?: return Result.failure()
    val id = inputData.getString("id") ?: return Result.failure()
    val actor = inputData.getString("actor") ?: owner
    // The next sign-in resumes journaled work; do not wake jobs for an inactive account.
    if (currentUserId() != actor) return Result.success()
    return try {
      withTimeout(60_000) {
        val completed = synchronize(owner, home, id, actor, inputData.getBoolean("delete", false))
        if (completed) Result.success() else Result.retry()
      }
    } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
      Result.retry()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      android.util.Log.w(RECEIPT_SYNC_TAG, "Receipt synchronization will retry", failure)
      Result.retry()
    }
  }

  companion object {
    /** Reuses one job per receipt; execution survives navigation and process restarts. */
    fun enqueue(
        context: Context,
        receipt: InventorySubmission,
        delete: Boolean = false,
        replace: Boolean = true
    ) {
      WorkManager.getInstance(context)
          .enqueueUniqueWork(
              "receipt-sync-${receipt.ownerId}-${receipt.id}",
              if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
              OneTimeWorkRequestBuilder<ReceiptSyncWorker>()
                  .setInputData(
                      workDataOf(
                          "owner" to receipt.ownerId,
                          "delete" to delete,
                          "actor" to
                              (FirebaseAuth.getInstance().currentUser?.uid ?: receipt.ownerId),
                          "home" to receipt.householdId,
                          "id" to receipt.id))
                  .setConstraints(
                      Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                  .build())
    }
  }
}

private suspend fun upload(
    context: Context,
    store: InventorySubmissionStore,
    receipt: InventorySubmission
) {
  store.details(receipt.ownerId, receipt.householdId, receipt.id)?.let {
    com.android.sharemate.model.receipt
        .FirebaseReceiptCloudRepository(context)
        .upload(com.android.sharemate.model.receipt.ReceiptCloudEntry(receipt, it))
  }
}
