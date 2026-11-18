package com.android.sharemate.ui.receipts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.item.InventorySubmissionStatus
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.item.ItemWrite
import com.android.sharemate.model.receipt.ReceiptPhotoStore
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
) : ViewModel() {
  private val mutableReceipts = MutableStateFlow<List<RetainedReceipt>>(emptyList())
  val receipts = mutableReceipts.asStateFlow()
  private val observations = mutableMapOf<String, Job>()
  private val submissions = mutableSetOf<String>()
  private val editMutex = Mutex()
  private val mutableError = MutableStateFlow(false)
  val error = mutableError.asStateFlow()

  init {
    viewModelScope.launch { refresh() }
  }

  /** Loads local receipts and resumes server acknowledgement observations after process restart. */
  suspend fun refresh() {
    try {
      val entries =
          store.list(ownerId, householdId).map { submission ->
            val details = store.details(ownerId, householdId, submission.id)
            val prior = receipts.value.firstOrNull { it.submission.id == submission.id }
            RetainedReceipt(
                submission,
                details,
                prior?.phase
                    ?: if (submission.draft) InventorySavePhase.READY
                    else if (details?.savedAtMillis != null) InventorySavePhase.CONFIRMED
                    else InventorySavePhase.PENDING)
          }
      mutableReceipts.value = entries
      entries.filter { it.phase == InventorySavePhase.PENDING }.forEach { retry(it.submission.id) }
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
  suspend fun editDraft(id: String, items: List<StagedItem>): Boolean =
      try {
        editMutex.withLock {
          val current = receipts.value.firstOrNull { it.submission.id == id }
          if (current?.submission?.draft != true) return@withLock false
          val updated = InventorySubmission(id, ownerId, householdId, items, draft = true)
          store.save(updated)
          mutableReceipts.value =
              receipts.value.map {
                if (it.submission.id == id && it.submission.draft) it.copy(submission = updated)
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
          is ItemWrite.Rejected -> setPhase(id, InventorySavePhase.FAILED, submission)
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
          is ItemWrite.Rejected -> setPhase(id, InventorySavePhase.FAILED)
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

  private fun observe(submission: InventorySubmission) {
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
