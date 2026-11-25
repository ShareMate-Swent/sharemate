// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import android.util.Log
import com.android.sharemate.model.receipt.RECEIPT_INITIAL_RETRY_MILLIS
import com.android.sharemate.model.receipt.RECEIPT_MAX_RETRY_MILLIS
import com.android.sharemate.model.receipt.RECEIPT_SYNC_TAG
import com.android.sharemate.model.receipt.ReceiptCloudEntry
import com.android.sharemate.model.receipt.ReceiptCloudPermanentException
import com.android.sharemate.model.receipt.ReceiptCloudRepository
import com.android.sharemate.model.receipt.nextReceiptRetry
import com.android.sharemate.model.staging.InventorySubmissionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Coordinates receipt recovery and cloud merges within the caller's lifecycle scope. */
class ReceiptHistorySync(
    private val store: InventorySubmissionStore,
    private val cloud: ReceiptCloudRepository,
    private val ownerId: String,
    private val householdId: String,
) {
  private val mergePolicy = ReceiptRemoteMergePolicy()
  private val failures = mutableSetOf<Any>()
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
            cloud.observe(ownerId, householdId).collectLatest { entries ->
              recovered()
              merge(entries, onChanged)
            }
          }
        }
      }

  private suspend fun merge(entries: List<ReceiptCloudEntry>, onChanged: suspend () -> Unit) =
      coroutineScope {
        launch {
          retry(Operation.CACHE) { recovered ->
            store.cacheShared(ownerId, householdId, entries)
            onChanged()
            recovered()
          }
        }
        for (entry in entries.filter { it.submission.ownerId == ownerId }) {
          val id = entry.submission.id
          launch {
            var saved = false
            try {
              retry(id) { recovered ->
                try {
                  if (!store.isDeleted(ownerId, householdId, id) &&
                      store.pendingCorrection(ownerId, householdId, id) == null &&
                      (saved ||
                          mergePolicy.shouldApplyRemote(
                              store.loadById(ownerId, householdId, id),
                              store.details(ownerId, householdId, id),
                              entry,
                              store.isDeleted(ownerId, householdId, id),
                              false))) {
                    if (!saved) {
                      store.save(entry.submission)
                      saved = true
                    }
                    store.saveDetails(entry.details)
                    onChanged()
                  }
                } catch (cancelled: CancellationException) {
                  throw cancelled
                } catch (failure: Exception) {
                  // A concurrent local deletion wins over this now-obsolete snapshot.
                  if (!store.isDeleted(ownerId, householdId, id)) throw failure
                }
                recovered()
              }
            } finally {
              setFailed(id, false)
            }
          }
        }
      }

  private suspend fun retry(operation: Any, action: suspend (() -> Unit) -> Unit) {
    var backoff = RECEIPT_INITIAL_RETRY_MILLIS
    while (true) {
      try {
        action {
          setFailed(operation, false)
          backoff = RECEIPT_INITIAL_RETRY_MILLIS
        }
        return
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        setFailed(operation, true)
        // Do not log exception messages or payloads, which can contain receipt/account data.
        Log.w(RECEIPT_SYNC_TAG, "Receipt operation failed (${failure.javaClass.simpleName})")
        if (failure is ReceiptCloudPermanentException) return
      }
      delay(backoff)
      backoff = nextReceiptRetry(backoff, RECEIPT_MAX_RETRY_MILLIS)
    }
  }

  @Synchronized
  private fun setFailed(operation: Any, failed: Boolean) {
    if (failed) failures.add(operation) else failures.remove(operation)
    mutableError.value = failures.isNotEmpty()
  }

  private enum class Operation {
    RECOVERY,
    DELETIONS,
    RECEIPTS,
    CACHE
  }
}
