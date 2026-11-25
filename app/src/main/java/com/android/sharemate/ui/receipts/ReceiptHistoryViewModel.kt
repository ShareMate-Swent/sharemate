// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.finance.ExpenseSubmissionRepository
import com.android.sharemate.model.item.ExpenseProgress
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.item.InventorySubmissionStatus
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.item.ItemWrite
import com.android.sharemate.model.receipt.ReceiptCloudRepository
import com.android.sharemate.model.receipt.ReceiptPhotoStore
import com.android.sharemate.model.receipt.normalizeReceiptTitle
import com.android.sharemate.model.staging.InventorySubmissionStore
import com.android.sharemate.model.staging.ReceiptDetails
import com.android.sharemate.model.staging.StagedItem
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One locally retained receipt and its last known server acknowledgement state. */
data class RetainedReceipt(
    val submission: InventorySubmission,
    val details: ReceiptDetails?,
    val phase: InventorySavePhase,
)

/** Owns on-device receipt history for one authenticated account and household. */
class ReceiptHistoryViewModel(
    private val repository: ItemRepository,
    private val store: InventorySubmissionStore,
    private val photos: ReceiptPhotoStore,
    private val ownerId: String,
    private val householdId: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val expenses: ExpenseSubmissionRepository,
    private val cloud: ReceiptCloudRepository,
) : ViewModel() {
  private val mutableReceipts = MutableStateFlow<List<RetainedReceipt>>(emptyList())
  /** Receipts belonging to this account and household, including pending and failed saves. */
  val receipts = mutableReceipts.asStateFlow()
  private val observations = mutableMapOf<String, Job>()
  private val submissions = mutableSetOf<String>()
  private val editMutex = Mutex()
  private val remoteMerge = ReceiptRemoteMergePolicy()
  private val mutableError = MutableStateFlow(false)
  /** True when local history could not be read or edited; successful operations clear it. */
  val error = mutableError.asStateFlow()
  private val mutableScanToReview = MutableStateFlow<String?>(null)
  /** Newly scanned receipt whose review has yet to be opened. */
  val scanToReview = mutableScanToReview.asStateFlow()
  private val mutableSavedReview = MutableStateFlow<String?>(null)
  private var reviewRequest = 0
  /**
   * Receipt whose edits were saved successfully; the visible review consumes this navigation
   * request.
   */
  val savedReview = mutableSavedReview.asStateFlow()

  /**
   * Refreshes history in this ViewModel's lifetime and exposes storage failures through [error].
   */
  fun reload() {
    viewModelScope.launch { refresh() }
  }

  /** Persists draft rows without navigating; cancellation follows the ViewModel lifetime. */
  fun updateDraft(id: String, items: List<StagedItem>, title: String? = null) {
    viewModelScope.launch { editDraft(id, items, title) }
  }

  /** Titles are presentation metadata and may be changed without replaying a saved purchase. */
  suspend fun rename(id: String, title: String): Boolean =
      try {
        editMutex.withLock {
          if (receipts.value.none { it.submission.id == id }) return@withLock false
          val details =
              (store.details(ownerId, householdId, id)
                      ?: ReceiptDetails(id, ownerId, householdId, "", now()))
                  .copy(title = normalizeReceiptTitle(title))
          store.saveDetails(details)
          mutableReceipts.value =
              receipts.value.map { if (it.submission.id == id) it.copy(details = details) else it }
          mutableError.value = false
          true
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableError.value = true
        false
      }

  /**
   * Saves edits and requests leaving this receipt only on success; failures keep the review open.
   */
  fun saveAndLeave(id: String, items: List<StagedItem>, title: String? = null) {
    val request = ++reviewRequest
    mutableSavedReview.value = null
    viewModelScope.launch {
      if (editDraft(id, items, title) && request == reviewRequest) mutableSavedReview.value = id
    }
  }

  /** Clears a consumed or abandoned review navigation request. */
  fun clearSavedReview() {
    reviewRequest++
    mutableSavedReview.value = null
  }

  /**
   * Deletes a confirmed purchase remotely before removing local data; failures remain retryable.
   */
  suspend fun deleteReceipt(id: String): Boolean =
      try {
        editMutex.withLock {
          val current = store.loadById(ownerId, householdId, id) ?: return@withLock false
          check(id !in submissions && store.pendingCorrection(ownerId, householdId, id) == null)
          check(
              current.draft ||
                  receipts.value.firstOrNull { it.submission.id == id }?.phase ==
                      InventorySavePhase.CONFIRMED)
          if (!current.draft) expenses.deleteReceipt(householdId, id)
          photos.delete(id)
          store.deleteReceipt(ownerId, householdId, id)
          observations.remove(id)?.cancel()
          mutableReceipts.value = receipts.value.filterNot { it.submission.id == id }
          mutableError.value = false
          true
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableError.value = true
        false
      }

  /** Discards only an unsubmitted draft; a queued or saved purchase must never be removed here. */
  suspend fun discardDraft(id: String): Boolean =
      try {
        editMutex.withLock {
          val current = store.loadById(ownerId, householdId, id) ?: return@withLock false
          if (!current.draft || id in submissions) return@withLock false
          store.deleteDraft(ownerId, householdId, id)
          observations.remove(id)?.cancel()
          mutableReceipts.value = receipts.value.filterNot { it.submission.id == id }
          withContext(NonCancellable) {
            try {
              photos.delete(id)
            } catch (_: Exception) {
              /* Unreferenced local photo only. */
            }
          }
          mutableError.value = false
          true
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableError.value = true
        false
      }

  /**
   * Retains a scan before the picker releases its source; exposes failures and propagates them to
   * the picker.
   */
  suspend fun retainScan(uri: String, items: List<StagedItem>): String =
      try {
        addDraft(uri, items).also { mutableScanToReview.value = it }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        mutableError.value = true
        throw failure
      }

  /** Consumes the automatic review request if [id] still matches the pending receipt. */
  fun scanReviewOpened(id: String) {
    mutableScanToReview.compareAndSet(id, null)
  }

  init {
    viewModelScope.launch {
      refresh()
      try {
        store.resumeSync(ownerId, householdId)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableError.value = true
      }
    }
    viewModelScope.launch {
      try {
        cloud.observeDeleted(ownerId, householdId).collect { ids ->
          store.applyCloudDeletions(ownerId, householdId, ids)
          refresh()
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableError.value = true
      }
    }
    viewModelScope.launch {
      try {
        cloud.observe(ownerId, householdId).collect { entries ->
          store.cacheShared(ownerId, householdId, entries)
          for (entry in entries.filter { it.submission.ownerId == ownerId }) {
            val local = store.loadById(ownerId, householdId, entry.submission.id)
            val localDetails = store.details(ownerId, householdId, entry.submission.id)
            if (remoteMerge.shouldApplyRemote(
                local,
                localDetails,
                entry,
                store.isDeleted(ownerId, householdId, entry.submission.id),
                store.pendingCorrection(ownerId, householdId, entry.submission.id) != null)) {
              store.save(entry.submission)
              store.saveDetails(entry.details)
            }
          }
          refresh()
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableError.value = true
      }
    }
  }

  /** Loads local receipts and resumes server acknowledgement observations after process restart. */
  suspend fun refresh() {
    try {
      val entries =
          (store.list(ownerId, householdId) + store.sharedReceipts(ownerId, householdId)).map {
              submission ->
            val details = store.details(submission.ownerId, householdId, submission.id)
            val prior = receipts.value.firstOrNull { it.submission.id == submission.id }
            RetainedReceipt(
                submission,
                details,
                if (store.pendingCorrection(submission.ownerId, householdId, submission.id) !=
                    null) {
                  InventorySavePhase.PENDING
                } else if (submission.expense != null) {
                  if (submission.expenseProgress == ExpenseProgress.CONFIRMED)
                      InventorySavePhase.CONFIRMED
                  else InventorySavePhase.PENDING
                } else
                    prior?.phase
                        ?: if (submission.draft) InventorySavePhase.READY
                        else if (details?.savedAtMillis != null) InventorySavePhase.CONFIRMED
                        else InventorySavePhase.PENDING)
          }
      mutableReceipts.value = entries
      entries
          .filter { it.phase == InventorySavePhase.PENDING && it.submission.expense == null }
          .forEach { observe(it.submission) }
      mutableError.value = false
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      mutableError.value = true
    }
  }

  /** Retains a new OCR result and its photo before publishing it to the history. */
  suspend fun addDraft(sourceUri: String, items: List<StagedItem>): String {
    val id = UUID.randomUUID().toString()
    val submission = InventorySubmission(id, ownerId, householdId, items, draft = true)
    val photoUri = photos.retain(sourceUri, id)
    try {
      store.save(submission)
      store.saveDetails(ReceiptDetails(id, ownerId, householdId, photoUri, now()))
    } catch (failure: Exception) {
      withContext(NonCancellable) {
        try {
          store.deleteDraft(ownerId, householdId, id)
        } finally {
          photos.delete(id)
        }
      }
      throw failure
    }
    refresh()
    return id
  }

  /** Persists edits for one draft without altering any other receipt. */
  suspend fun editDraft(
      id: String,
      items: List<StagedItem>,
      title: String? = null,
      draftPaidBy: String? = null,
      draftParticipants: Set<String>? = null,
  ): Boolean =
      try {
        editMutex.withLock {
          val current = receipts.value.firstOrNull { it.submission.id == id }
          if (current?.submission?.draft != true) return@withLock false
          val updated = InventorySubmission(id, ownerId, householdId, items, draft = true)
          store.save(updated)
          val details =
              if (title != null) {
                val previous =
                    store.details(ownerId, householdId, id)
                        ?: ReceiptDetails(id, ownerId, householdId, "", now())
                previous
                    .copy(
                        title = normalizeReceiptTitle(title),
                        draftPaidBy =
                            if (draftParticipants != null) draftPaidBy else previous.draftPaidBy,
                        draftParticipants = draftParticipants ?: previous.draftParticipants,
                    )
                    .also { store.saveDetails(it) }
              } else current.details
          mutableReceipts.value =
              receipts.value.map {
                if (it.submission.id == id && it.submission.draft)
                    it.copy(submission = updated, details = details)
                else it
              }
          mutableError.value = false
          true
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableError.value = true
        false
      }

  /** Queues one reviewed draft under its stable ID; pending receipts cannot be submitted twice. */
  fun confirm(id: String, items: List<StagedItem>) {
    val current = receipts.value.firstOrNull { it.submission.id == id } ?: return
    if (!current.submission.draft || !submissions.add(id)) return
    val submission =
        try {
          InventorySubmission(id, ownerId, householdId, items)
        } catch (_: IllegalArgumentException) {
          submissions.remove(id)
          setPhase(id, InventorySavePhase.FAILED)
          return
        }
    mutableReceipts.value =
        receipts.value.map {
          if (it.submission.id == id)
              it.copy(submission = submission, phase = InventorySavePhase.SUBMITTING)
          else it
        }
    viewModelScope.launch {
      var durable = false
      try {
        editMutex.withLock { store.save(submission) }
        durable = true
        setPhase(id, InventorySavePhase.PENDING, submission)
        val write = repository.addItems(submission)
        when (write) {
          is ItemWrite.Rejected -> observe(submission, restart = true)
          is ItemWrite.Queued -> {
            observe(submission)
            if (write.confirmation.await().isSuccess) confirmSaved(submission)
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (durable) setPhase(id, InventorySavePhase.PENDING, submission)
        else setPhase(id, InventorySavePhase.FAILED, current.submission)
      } finally {
        submissions.remove(id)
      }
    }
  }

  /** Replays a failed submission with its original identity and reviewed rows. */
  fun retry(id: String) {
    val current = receipts.value.firstOrNull { it.submission.id == id } ?: return
    if (current.submission.draft) {
      confirm(id, current.submission.items)
      return
    }
    if (current.phase != InventorySavePhase.FAILED && current.phase != InventorySavePhase.PENDING)
        return
    if (!submissions.add(id)) return
    val submission = current.submission
    setPhase(id, InventorySavePhase.PENDING)
    viewModelScope.launch {
      try {
        val write = repository.addItems(submission)
        when (write) {
          is ItemWrite.Rejected -> observe(submission, restart = true)
          is ItemWrite.Queued -> {
            observe(submission)
            if (write.confirmation.await().isSuccess) confirmSaved(submission)
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        setPhase(id, InventorySavePhase.PENDING)
      } finally {
        submissions.remove(id)
      }
    }
  }

  private fun observe(submission: InventorySubmission, restart: Boolean = false) {
    if (restart) observations.remove(submission.id)?.cancel()
    if (observations[submission.id]?.isActive == true) return
    observations[submission.id] =
        viewModelScope.launch {
          try {
            repository.observeSubmission(submission).collect { status ->
              when (status) {
                InventorySubmissionStatus.CONFIRMED -> confirmSaved(submission)
                InventorySubmissionStatus.MISSING ->
                    if (receipts.value.firstOrNull { it.submission.id == submission.id }?.phase !=
                        InventorySavePhase.CONFIRMED) {
                      setPhase(submission.id, InventorySavePhase.FAILED)
                      submissions.remove(submission.id)
                    }
                InventorySubmissionStatus.PENDING -> Unit
              }
            }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            // The durable submission remains pending until a later observation can verify it.
          }
        }
  }

  private suspend fun confirmSaved(submission: InventorySubmission) {
    val current = receipts.value.firstOrNull { it.submission.id == submission.id } ?: return
    if (current.phase == InventorySavePhase.CONFIRMED) return
    val details = current.details?.copy(savedAtMillis = now())
    details?.let { store.saveDetails(it) }
    mutableReceipts.value =
        receipts.value.map {
          if (it.submission.id == submission.id) it.copy(details = details) else it
        }
    setPhase(submission.id, InventorySavePhase.CONFIRMED)
  }

  private fun setPhase(
      id: String,
      phase: InventorySavePhase,
      submission: InventorySubmission? = null
  ) {
    mutableReceipts.value =
        receipts.value.map {
          if (it.submission.id == id)
              it.copy(submission = submission ?: it.submission, phase = phase)
          else it
        }
  }
}
