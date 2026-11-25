// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import android.util.Log
import com.android.sharemate.model.receipt.ReceiptCloudEntry
import com.android.sharemate.model.receipt.ReceiptCloudRepository
import com.android.sharemate.model.staging.InventorySubmissionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Coordinates receipt recovery and cloud merges within the caller's lifecycle scope. */
class ReceiptHistorySync(
    private val store: InventorySubmissionStore,
    private val cloud: ReceiptCloudRepository,
    private val ownerId: String,
    private val householdId: String,
) {
  private val mergePolicy = ReceiptRemoteMergePolicy()
  private val failures = mutableSetOf<Operation>()
  private val mutableError = MutableStateFlow(false)
  /** Remains true until every failed operation succeeds; local refresh cannot clear it. */
  val error = mutableError.asStateFlow()

  /**
   * Starts one recovery job and both observers. The returned job and all retries are children of
   * [scope]; cancelling it stops subscriptions and backoff. Call once per coordinator instance.
   */
  fun start(scope: CoroutineScope, onChanged: suspend () -> Unit): Job =
      scope.launch {
        launch {
          retry(Operation.RECOVERY) { recovered ->
            store.resumeSync(ownerId, householdId)
            recovered()
          }
        }
        launch {
          retry(Operation.DELETIONS) { recovered ->
            cloud.observeDeleted(ownerId, householdId).collect { ids ->
              store.applyCloudDeletions(ownerId, householdId, ids)
              onChanged()
              recovered()
            }
          }
        }
        launch {
          retry(Operation.RECEIPTS) { recovered ->
            cloud.observe(ownerId, householdId).collect { entries ->
              merge(entries)
              onChanged()
              recovered()
            }
          }
        }
      }

  private suspend fun merge(entries: List<ReceiptCloudEntry>) {
    store.cacheShared(ownerId, householdId, entries)
    for (entry in entries.filter { it.submission.ownerId == ownerId }) {
      val id = entry.submission.id
      if (mergePolicy.shouldApplyRemote(
          store.loadById(ownerId, householdId, id),
          store.details(ownerId, householdId, id),
          entry,
          store.isDeleted(ownerId, householdId, id),
          store.pendingCorrection(ownerId, householdId, id) != null)) {
        store.save(entry.submission)
        store.saveDetails(entry.details)
      }
    }
  }

  private suspend fun retry(operation: Operation, action: suspend (() -> Unit) -> Unit) {
    var backoff = INITIAL_RETRY_MILLIS
    while (true) {
      try {
        action {
          setFailed(operation, false)
          backoff = INITIAL_RETRY_MILLIS
        }
        return
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        setFailed(operation, true)
        // Do not log exception messages or payloads, which can contain receipt/account data.
        Log.w("ReceiptSync", "$operation failed (${failure.javaClass.simpleName}); retrying")
      }
      delay(backoff)
      backoff = (backoff * 2).coerceAtMost(MAX_RETRY_MILLIS)
    }
  }

  @Synchronized
  private fun setFailed(operation: Operation, failed: Boolean) {
    if (failed) failures.add(operation) else failures.remove(operation)
    mutableError.value = failures.isNotEmpty()
  }

  private enum class Operation {
    RECOVERY,
    DELETIONS,
    RECEIPTS
  }

  private companion object {
    const val INITIAL_RETRY_MILLIS = 1_000L
    const val MAX_RETRY_MILLIS = 30_000L
  }
}
