// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.finance.ExpenseRejectedException
import com.android.sharemate.model.finance.ExpenseSubmissionRepository
import com.android.sharemate.model.finance.SharedExpense
import com.android.sharemate.model.item.ExpenseProgress
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.item.InventorySubmissionStatus
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.item.ItemWrite
import com.android.sharemate.model.staging.InventorySubmissionStore
import com.android.sharemate.model.staging.StagedItem
import com.android.sharemate.ui.staging.PurchaseReview
import com.android.sharemate.ui.staging.ReviewedPurchase
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    get() =
        submission?.expense == null &&
            (phase == InventorySavePhase.READY || phase == InventorySavePhase.FAILED)
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
    private val expenses: ExpenseSubmissionRepository? = null,
) : ViewModel() {
  private val mutableState = MutableStateFlow(InventorySaveState())
  /** Current operation and its retained reviewed rows; server confirmation alone marks success. */
  val state = mutableState.asStateFlow()
  private val mutableDeferred = MutableStateFlow<List<InventorySubmission>>(emptyList())
  val deferred = mutableDeferred.asStateFlow()
  private var observation: Job? = null
  private var submissionJob: Job? = null
  private var generation = 0
  private var expenseJob: Job? = null
  private var expenseRetry = CompletableDeferred<Unit>()

  init {
    restore()
  }

  private fun restore() {
    val token = ++generation
    mutableState.value = InventorySaveState()
    submissionJob =
        viewModelScope.launch {
          try {
            mutableDeferred.value = store.deferred(userId, householdId)
            val restored = store.load(userId, householdId)
            if (generation != token) return@launch
            mutableState.value =
                InventorySaveState(
                    if (restored == null || restored.draft) InventorySavePhase.READY
                    else InventorySavePhase.PENDING,
                    restored)
            if (canSave)
                restored
                    ?.takeUnless { it.draft }
                    ?.let {
                      if (it.inventoryConfirmed) finishInventory(it, token) else observe(it, token)
                    }
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
  fun confirm(items: List<StagedItem>): Boolean = confirmRows(items, null)

  /** Revalidate the live member selection before retaining an immutable purchase. */
  fun confirmPurchase(purchase: ReviewedPurchase, members: Map<String, String>): Boolean {
    if (expenses == null) return false
    val review =
        PurchaseReview.forUser(userId, members)
            .copy(paidBy = purchase.paidBy, participants = purchase.shares.keys)
    val preview = review.preview(purchase.items)
    if (preview.error != null ||
        preview.totalCents != purchase.totalCents ||
        preview.shares != purchase.shares)
        return false
    return confirmRows(purchase.items, purchase)
  }

  private fun confirmRows(items: List<StagedItem>, purchase: ReviewedPurchase?): Boolean {
    if (!canSave || !state.value.canConfirm) return false
    val identity = state.value.submission?.id ?: UUID.randomUUID().toString()
    val submission =
        try {
          InventorySubmission(
              identity,
              userId,
              householdId,
              items.toList(),
              expense =
                  purchase?.let {
                    SharedExpense(identity, householdId, it.paidBy, it.totalCents, it.shares)
                  })
        } catch (_: IllegalArgumentException) {
          mutableState.value =
              state.value.copy(phase = InventorySavePhase.FAILED, invalidInput = true)
          return false
        }
    enqueue(submission)
    return true
  }

  private fun enqueue(submission: InventorySubmission) {
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
                finishInventory(submission, token)
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
  }

  private fun finishInventory(
      submission: InventorySubmission,
      token: Int,
      retryRejected: Boolean = false
  ) {
    if (generation != token || state.value.phase == InventorySavePhase.CONFIRMED) return
    if (!submission.inventoryConfirmed && state.value.submission?.inventoryConfirmed == true) return
    if (submission.expense == null) {
      mutableState.value = InventorySaveState(InventorySavePhase.CONFIRMED, submission)
      return
    }
    if (expenseJob?.isActive == true) return
    val confirmed = submission.withProgress(true)
    mutableState.value =
        InventorySaveState(
            if (confirmed.expenseProgress == ExpenseProgress.CONFIRMED) InventorySavePhase.CONFIRMED
            else InventorySavePhase.PENDING,
            confirmed)
    if (confirmed.expenseProgress == ExpenseProgress.CONFIRMED ||
        expenses == null ||
        (confirmed.expenseProgress == ExpenseProgress.REJECTED && !retryRejected))
        return
    expenseRetry = CompletableDeferred()
    expenseJob =
        viewModelScope.launch {
          var retained = confirmed
          var backoff = 5_000L
          while (generation == token) {
            try {
              // Persist inventory confirmation and uncertainty before crossing the remote boundary.
              retained = retained.withProgress(true, ExpenseProgress.UNKNOWN)
              store.save(retained)
              if (generation != token) return@launch
              check(expenses.recordExpense(checkNotNull(retained.expense)) == retained.expense)
              retained = retained.withProgress(true, ExpenseProgress.CONFIRMED)
              store.save(retained)
              if (generation == token)
                  mutableState.value = InventorySaveState(InventorySavePhase.CONFIRMED, retained)
              return@launch
            } catch (cancelled: CancellationException) {
              throw cancelled
            } catch (failure: Exception) {
              val progress =
                  if (failure is ExpenseRejectedException) ExpenseProgress.REJECTED
                  else ExpenseProgress.UNKNOWN
              retained = retained.withProgress(true, progress)
              if (generation == token)
                  mutableState.value = InventorySaveState(InventorySavePhase.PENDING, retained)
              try {
                store.save(retained)
              } catch (cancelled: CancellationException) {
                throw cancelled
              } catch (_: Exception) {
                /* Original payload remains durable. */
              }
              if (progress == ExpenseProgress.REJECTED) expenseRetry.await()
              else withTimeoutOrNull(backoff) { expenseRetry.await() }
              expenseRetry = CompletableDeferred()
              backoff = (backoff * 2).coerceAtMost(60_000L)
            }
          }
        }
  }

  /**
   * Retries retained corrections after a definite failure, reusing the original submission ID. An
   * uncertain recovery retries observation only; it never replays a potentially pending write.
   */
  fun retry() {
    val current = state.value
    current.submission
        ?.takeIf { it.expense != null }
        ?.let {
          if (!canSave ||
              current.phase == InventorySavePhase.CONFIRMED ||
              current.phase == InventorySavePhase.SUBMITTING)
              return
          if (it.inventoryConfirmed) {
            if (expenseJob?.isActive == true) expenseRetry.complete(Unit)
            else finishInventory(it, generation, retryRejected = true)
          } else if (current.phase == InventorySavePhase.FAILED) enqueue(it)
          else if (current.phase == InventorySavePhase.RECOVERY_FAILED) observe(it, generation)
          return
        }
    if (current.invalidInput) return
    if (current.phase == InventorySavePhase.RECOVERY_FAILED) {
      current.submission?.let {
        mutableState.value = current.copy(phase = InventorySavePhase.PENDING)
        observe(it, generation)
      } ?: restore()
    } else current.submission?.let { confirm(it.items) }
  }

  /** Releases a rejected purchase only after its immutable payload has been archived durably. */
  fun setAside() {
    val retained = state.value.submission ?: return
    if (!canSave ||
        state.value.phase != InventorySavePhase.PENDING ||
        !retained.inventoryConfirmed ||
        retained.expenseProgress != ExpenseProgress.REJECTED)
        return
    mutableState.value = state.value.copy(phase = InventorySavePhase.SUBMITTING)
    val token = ++generation
    viewModelScope.launch {
      observation?.cancelAndJoin()
      submissionJob?.cancelAndJoin()
      expenseJob?.cancelAndJoin()
      try {
        store.save(retained)
        store.setAside(retained)
        mutableDeferred.value = store.deferred(userId, householdId)
        if (generation == token) mutableState.value = InventorySaveState(InventorySavePhase.READY)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (generation == token)
            mutableState.value = InventorySaveState(InventorySavePhase.RECOVERY_FAILED, retained)
      }
    }
  }

  /** Restores a deferred operation without changing its identity or replaying inventory. */
  fun recoverDeferred(id: String? = mutableDeferred.value.firstOrNull()?.id) {
    if (!canSave || !state.value.canStartScan || state.value.submission?.draft == true) return
    val retained = mutableDeferred.value.firstOrNull { it.id == id } ?: return
    mutableState.value = state.value.copy(phase = InventorySavePhase.SUBMITTING)
    viewModelScope.launch {
      try {
        store.save(retained)
        restore()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableState.value = InventorySaveState(InventorySavePhase.RECOVERY_FAILED)
      }
    }
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
              if (generation != token ||
                  state.value.phase == InventorySavePhase.CONFIRMED ||
                  state.value.submission?.inventoryConfirmed == true)
                  return@collect
              if (status == InventorySubmissionStatus.CONFIRMED) {
                finishInventory(submission, token)
                return@collect
              }
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
            if (generation == token &&
                state.value.phase != InventorySavePhase.CONFIRMED &&
                state.value.submission?.inventoryConfirmed != true) {
              mutableState.value =
                  InventorySaveState(InventorySavePhase.RECOVERY_FAILED, submission)
            }
          }
        }
  }
}
