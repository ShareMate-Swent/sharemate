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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

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
        val refreshes = Channel<Unit>(Channel.CONFLATED)
        launch {
          for (request in refreshes) {
            // Batch ready writers, then keep at most one refresh queued during the read.
            yield()
            while (refreshes.tryReceive().isSuccess) {}
            retry(Operation.REFRESH) { recovered ->
              onChanged()
              recovered()
            }
          }
        }
        try {
          observeChanges { refreshes.trySend(Unit) }
        } finally {
          refreshes.close()
        }
      }

  private suspend fun observeChanges(requestRefresh: () -> Unit) = coroutineScope {
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
          requestRefresh()
          recovered()
        }
      }
    }
    launch {
      retry(Operation.RECEIPTS) { recovered ->
        cloud.observe(ownerId, householdId).collectLatest { entries ->
          recovered()
          merge(entries) { requestRefresh() }
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
          launch { mergeEntry(entry, onChanged) }
        }
      }

  private suspend fun mergeEntry(entry: ReceiptCloudEntry, onChanged: suspend () -> Unit) {
    val id = entry.submission.id
    var saved = false
    try {
      retry(id) { recovered ->
        if (mergeEntryAttempt(entry, saved) { saved = true }) onChanged()
        recovered()
      }
    } finally {
      setFailed(id, false)
    }
  }

  private suspend fun mergeEntryAttempt(
      entry: ReceiptCloudEntry,
      saved: Boolean,
      onSaved: () -> Unit,
  ): Boolean =
      try {
        if (!shouldMergeEntry(entry, saved)) false
        else {
          if (!saved) {
            store.save(entry.submission)
            onSaved()
          }
          store.saveDetails(entry.details)
          true
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        // Recheck only after a failed write: a concurrent local deletion wins.
        if (!store.isDeleted(ownerId, householdId, entry.submission.id)) throw failure
        false
      }

  private suspend fun shouldMergeEntry(entry: ReceiptCloudEntry, saved: Boolean): Boolean {
    val id = entry.submission.id
    val deleted = store.isDeleted(ownerId, householdId, id)
    val correctionPending = store.pendingCorrection(ownerId, householdId, id) != null
    if (deleted || correctionPending) return false
    if (saved) return true
    return mergePolicy.shouldApplyRemote(
        store.loadById(ownerId, householdId, id),
        store.details(ownerId, householdId, id),
        entry,
        deleted,
        correctionPending)
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
    CACHE,
    REFRESH
  }
}
