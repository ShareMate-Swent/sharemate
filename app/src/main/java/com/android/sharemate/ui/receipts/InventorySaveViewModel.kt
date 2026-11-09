// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.item.InventorySubmissionStatus
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.item.ItemWrite
import com.android.sharemate.model.staging.InventorySubmissionStore
import com.android.sharemate.model.staging.StagedItem
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Receipt save progress. [LOADING] reads the journal and [READY] allows draft review. [SUBMITTING]
 * persists the draft or reviewed rows, [PENDING] awaits the server, and [CONFIRMED] follows server
 * acknowledgement. [FAILED] reports a failed attempt and [RECOVERY_FAILED] an uncertain recovery.
 */
enum class InventorySavePhase {
  LOADING,
  READY,
  SUBMITTING,
  PENDING,
  CONFIRMED,
  FAILED,
  RECOVERY_FAILED
}

/**
 * Receipt save [phase] and retained [submission] for the current user and household. [invalidInput]
 * marks rows that need correction before retrying.
 */
data class InventorySaveState(
    val phase: InventorySavePhase = InventorySavePhase.LOADING,
    val submission: InventorySubmission? = null,
    val invalidInput: Boolean = false,
) {
  /** A new scan may begin in [InventorySavePhase.READY] or after server confirmation. */
  val canStartScan: Boolean
    get() = phase == InventorySavePhase.READY || phase == InventorySavePhase.CONFIRMED

  /**
   * The phase allows confirmation in [InventorySavePhase.READY] or [InventorySavePhase.FAILED]. The
   * ViewModel still checks whether saving is enabled and validates the reviewed rows.
   */
  val canConfirm: Boolean
    get() = phase == InventorySavePhase.READY || phase == InventorySavePhase.FAILED
}

/**
 * Saves one reviewed receipt for a fixed authenticated session/household. Observation belongs to
 * this navigation-entry ViewModel and is cancelled on teardown; SDK writes continue independently.
 * The durable store and Firestore queue recover the operation when this owner is recreated.
 */
class InventorySaveViewModel(
    private val repository: ItemRepository,
    private val store: InventorySubmissionStore,
    private val userId: String,
    private val householdId: String,
    private val canSave: Boolean = true,
) : ViewModel() {
  private val mutableState = MutableStateFlow(InventorySaveState())
  /** Current operation and its retained reviewed rows; server confirmation alone marks success. */
  val state = mutableState.asStateFlow()
  private var observation: Job? = null
  private var submissionJob: Job? = null
  private var generation = 0

  init {
    restore()
  }

  private fun restore() {
    val token = ++generation
    mutableState.value = InventorySaveState()
    submissionJob =
        viewModelScope.launch {
          try {
            val restored = store.load(userId, householdId)
            if (generation != token) return@launch
            mutableState.value =
                InventorySaveState(
                    if (restored == null || restored.draft) InventorySavePhase.READY
                    else InventorySavePhase.PENDING,
                    restored)
            restored?.takeUnless { it.draft }?.let { observe(it, token) }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            if (generation == token)
                mutableState.value = InventorySaveState(InventorySavePhase.RECOVERY_FAILED)
          }
        }
  }

  /**
   * Persists corrected [items] before enqueueing them. Duplicate/pending confirmations are ignored.
   * Invalid input or storage failure retains review data and reports failure without a remote
   * write. Cancellation never claims to revoke a batch that the SDK has already accepted. Returns
   * false when confirmation is unavailable or validation fails; keep the review open.
   */
  fun confirm(items: List<StagedItem>): Boolean {
    if (!canSave || !state.value.canConfirm) return false
    val submission =
        try {
          InventorySubmission(
              state.value.submission?.id ?: UUID.randomUUID().toString(),
              userId,
              householdId,
              items.toList())
        } catch (_: IllegalArgumentException) {
          mutableState.value =
              state.value.copy(phase = InventorySavePhase.FAILED, invalidInput = true)
          return false
        }
    mutableState.value = InventorySaveState(InventorySavePhase.SUBMITTING, submission)
    val token = ++generation
    observation?.cancel()
    submissionJob?.cancel()
    submissionJob =
        viewModelScope.launch {
          try {
            store.save(submission)
            if (generation != token) return@launch
            val write = repository.addItems(submission)
            mutableState.value = InventorySaveState(InventorySavePhase.PENDING, submission)
            observe(submission, token)
            if (write is ItemWrite.Queued) {
              val result = write.confirmation.await()
              if (result.isSuccess && generation == token) {
                mutableState.value = InventorySaveState(InventorySavePhase.CONFIRMED, submission)
              }
              // On rejection, the marker observation distinguishes failed writes from an
              // acknowledged
              // replay. Never turn a rejected replay into another insertion with a fresh identity.
            }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            if (generation == token)
                mutableState.value = InventorySaveState(InventorySavePhase.FAILED, submission)
          }
        }
    return true
  }

  /**
   * Retries retained corrections after a definite failure, reusing the original submission ID. An
   * uncertain recovery retries observation only; it never replays a potentially pending write.
   */
  fun retry() {
    val current = state.value
    if (current.invalidInput) return
    if (current.phase == InventorySavePhase.RECOVERY_FAILED) {
      current.submission?.let {
        mutableState.value = current.copy(phase = InventorySavePhase.PENDING)
        observe(it, generation)
      } ?: restore()
    } else current.submission?.let { confirm(it.items) }
  }

  /**
   * Durably retains a new reviewed [items] draft before publishing OCR results. Previous receipt
   * prices remain in its server marker. Failure/cancellation blocks further submission until
   * journal recovery, so a crash cannot replace this draft with an older saved receipt.
   */
  suspend fun beginNewScan(items: List<StagedItem>): Boolean {
    if (!state.value.canStartScan) return false
    val next =
        InventorySubmission(UUID.randomUUID().toString(), userId, householdId, items, draft = true)
    val token = ++generation
    observation?.cancel()
    submissionJob?.cancel()
    mutableState.value = state.value.copy(phase = InventorySavePhase.SUBMITTING)
    try {
      store.save(next)
      if (generation == token)
          mutableState.value = InventorySaveState(InventorySavePhase.READY, next)
    } catch (failure: Exception) {
      if (generation == token)
          mutableState.value = InventorySaveState(InventorySavePhase.RECOVERY_FAILED)
      throw failure
    }
    return generation == token
  }

  private fun observe(submission: InventorySubmission, token: Int) {
    observation?.cancel()
    observation =
        viewModelScope.launch {
          try {
            repository.observeSubmission(submission).collect { status ->
              if (generation != token || state.value.phase == InventorySavePhase.CONFIRMED)
                  return@collect
              mutableState.value =
                  InventorySaveState(
                      when (status) {
                        InventorySubmissionStatus.PENDING -> InventorySavePhase.PENDING
                        InventorySubmissionStatus.CONFIRMED -> InventorySavePhase.CONFIRMED
                        InventorySubmissionStatus.MISSING -> InventorySavePhase.FAILED
                      },
                      submission)
            }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            if (generation == token && state.value.phase != InventorySavePhase.CONFIRMED) {
              mutableState.value =
                  InventorySaveState(InventorySavePhase.RECOVERY_FAILED, submission)
            }
          }
        }
  }
}
