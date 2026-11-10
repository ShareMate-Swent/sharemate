// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.finance

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.finance.*
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.HouseholdRepository
import com.android.sharemate.model.profile.UserProfileRepository
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class SettlementPhase {
  IDLE,
  SUBMITTING,
  AWAITING_CONFIRMATION,
  FAILED,
  REJECTED
}

data class SettlementSubmission(
    val phase: SettlementPhase = SettlementPhase.IDLE,
    val operation: Settlement? = null,
    val optimistic: Boolean = false,
)

sealed interface SettlementUiState {
  data object Loading : SettlementUiState

  data object Unavailable : SettlementUiState

  data object Error : SettlementUiState

  data object Offline : SettlementUiState

  data object InvalidData : SettlementUiState

  data class Ready(
      val currentUserId: String,
      val summary: BalanceSummary,
      val submission: SettlementSubmission,
      val memberNames: Map<String, String> = emptyMap(),
  ) : SettlementUiState
}

/**
 * One account/household per navigation entry. The settlement dialog consumes this state. All writes
 * use the authoritative repository; a displayed debt only guards accidental input. An uncertain
 * failure retains the exact operation in saved state, including across recreation.
 */
class SettlementViewModel(
    private val finances: HouseholdFinanceRepository,
    private val households: HouseholdRepository,
    auth: AuthRepository,
    private val householdId: String,
    private val userId: String,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val calculationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val profiles: UserProfileRepository? = null,
    private val operationId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
  private data class Ledger(
      val snapshot: HouseholdFinanceSnapshot,
      val members: List<String>,
      val names: Map<String, String>
  )

  private val savedKey = "settlement_${householdId}_${userId}"
  private val restored =
      savedState.get<ArrayList<String>>(savedKey)?.let {
        Settlement(it[0], householdId, userId, it[1], it[2].toLong())
      }
  private val submission =
      MutableStateFlow(
          if (restored == null) SettlementSubmission()
          else SettlementSubmission(SettlementPhase.FAILED, restored))
  private val ledger = MutableStateFlow<Ledger?>(null)
  private val availability = MutableStateFlow<SettlementUiState>(SettlementUiState.Loading)
  private val mutableState = MutableStateFlow<SettlementUiState>(SettlementUiState.Loading)
  val state = mutableState.asStateFlow()
  private val retries = MutableStateFlow(0)
  private var writeJob: Job? = null
  private var renderedLedger: Ledger? = null

  init {
    viewModelScope.launch {
      combine(ledger, submission, availability) { records, payment, fallback ->
            Triple(records, payment, fallback)
          }
          .collectLatest { (records, payment, fallback) ->
            mutableState.value =
                if (records == null) fallback
                else {
                  val snapshot = records.snapshot
                  val pending = payment.operation?.takeIf { payment.optimistic }
                  val summary =
                      try {
                        withContext(calculationDispatcher) {
                          BalanceCalculator.calculate(
                              HouseholdFinanceSnapshot(
                                  householdId,
                                  snapshot.expenses,
                                  snapshot.settlements +
                                      listOfNotNull(
                                          pending?.takeUnless { candidate ->
                                            snapshot.settlements.any { it.id == candidate.id }
                                          })),
                              records.members)
                        }
                      } catch (cancelled: CancellationException) {
                        throw cancelled
                      } catch (_: Exception) {
                        mutableState.value = SettlementUiState.Error
                        return@collectLatest
                      }
                  renderedLedger = records
                  SettlementUiState.Ready(userId, summary, payment, records.names)
                }
          }
    }
    viewModelScope.launch {
      combine(auth.session, retries) { session, _ -> session }
          .collectLatest { session ->
            ledger.value = null
            availability.value = SettlementUiState.Loading
            val waiting = launch {
              delay(INITIAL_LOAD_TIMEOUT_MS)
              if (ledger.value == null) availability.value = SettlementUiState.Offline
            }
            writeJob?.cancel()
            if (submission.value.phase == SettlementPhase.REJECTED) {
              submission.value = SettlementSubmission()
            } else if (submission.value.operation != null) {
              submission.value =
                  submission.value.copy(phase = SettlementPhase.FAILED, optimistic = false)
            }
            try {
              val home = if (session?.uid == userId) households.getHousehold(householdId) else null
              if (home == null || home.type != FridgeType.SHARED || userId !in home.memberIds) {
                availability.value = SettlementUiState.Unavailable
              } else {
                val names =
                    profiles
                        ?.observeMembers(householdId)
                        ?.map { members ->
                          members
                              .mapNotNull { member ->
                                member.displayName
                                    ?.trim()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let { member.uid to it }
                              }
                              .toMap()
                        }
                        ?.onStart { emit(emptyMap()) }
                        ?.catch { failure ->
                          if (failure is CancellationException) throw failure
                          emit(emptyMap())
                        } ?: flowOf(emptyMap())
                combine(finances.observeHouseholdFinances(householdId), names) { snapshot, labels ->
                      snapshot to labels
                    }
                    .collect { (snapshot, labels) ->
                      require(snapshot.householdId == householdId)
                      // Validate the whole ledger before making it available for submissions.
                      withContext(calculationDispatcher) {
                        BalanceCalculator.calculate(snapshot, home.memberIds)
                      }
                      val pending = submission.value.operation
                      val recorded = snapshot.settlements.firstOrNull { it.id == pending?.id }
                      if (recorded != null) {
                        check(recorded == pending) { "Settlement operation mismatch" }
                        writeJob?.cancel()
                        savedState.remove<ArrayList<String>>(savedKey)
                        submission.value = SettlementSubmission()
                      } else if (submission.value.phase == SettlementPhase.REJECTED &&
                          snapshot != ledger.value?.snapshot) {
                        // Profile-only updates must not dismiss the rejection.
                        submission.value = SettlementSubmission()
                      }
                      ledger.value = Ledger(snapshot, home.memberIds, labels)
                      waiting.cancel()
                    }
              }
            } catch (cancelled: CancellationException) {
              throw cancelled
            } catch (failure: Exception) {
              ledger.value = null
              availability.value =
                  if (failure is IllegalArgumentException) SettlementUiState.InvalidData
                  else SettlementUiState.Error
              writeJob?.cancel()
            } finally {
              waiting.cancel()
            }
          }
    }
  }

  /** Returns false for invalid, stale, duplicate or unavailable submissions. */
  fun settle(debt: Debt, amountCents: Long): Boolean {
    val ready = state.value as? SettlementUiState.Ready ?: return false
    if (ledger.value == null ||
        ledger.value !== renderedLedger ||
        submission.value.phase !in setOf(SettlementPhase.IDLE, SettlementPhase.REJECTED) ||
        writeJob?.isActive == true ||
        debt.debtorId != userId ||
        debt !in ready.summary.debts ||
        amountCents <= 0 ||
        amountCents > debt.amountCents)
        return false
    val payment = Settlement(operationId(), householdId, userId, debt.creditorId, amountCents)
    savedState[savedKey] =
        arrayListOf(payment.id, payment.recipientId, payment.amountCents.toString())
    send(payment, true)
    return true
  }

  /** Retry the original payload even if the debt changed: the first attempt may have committed. */
  fun retrySettlement() {
    val ready = state.value as? SettlementUiState.Ready ?: return
    val payment = submission.value.operation ?: return
    if (ledger.value == null ||
        ledger.value !== renderedLedger ||
        submission.value.phase != SettlementPhase.FAILED ||
        writeJob?.isActive == true)
        return
    val canPreview =
        ready.summary.debts.any {
          it.debtorId == payment.payerId &&
              it.creditorId == payment.recipientId &&
              it.amountCents >= payment.amountCents
        }
    send(payment, canPreview)
  }

  private fun send(payment: Settlement, optimistic: Boolean) {
    submission.value = SettlementSubmission(SettlementPhase.SUBMITTING, payment, optimistic)
    writeJob =
        viewModelScope.launch {
          try {
            require(finances.recordSettlement(payment) == payment) {
              "Settlement acknowledgement mismatch"
            }
            ensureActive()
            if (submission.value.operation == payment) {
              submission.value =
                  submission.value.copy(phase = SettlementPhase.AWAITING_CONFIRMATION)
            }
          } catch (cancelled: CancellationException) {
            if (submission.value.operation == payment) {
              submission.value = SettlementSubmission(SettlementPhase.FAILED, payment)
            }
            throw cancelled
          } catch (_: SettlementRejectedException) {
            if (submission.value.operation == payment) {
              savedState.remove<ArrayList<String>>(savedKey)
              submission.value = SettlementSubmission(SettlementPhase.REJECTED, payment)
            }
          } catch (_: Exception) {
            if (submission.value.operation == payment) {
              submission.value = SettlementSubmission(SettlementPhase.FAILED, payment)
            }
          }
        }
  }

  fun retryLoading() {
    if (state.value == SettlementUiState.Error || state.value == SettlementUiState.Offline)
        retries.value += 1
  }

  companion object {
    /** Time before a missing server result is presented as offline while observation continues. */
    internal const val INITIAL_LOAD_TIMEOUT_MS = 10_000L
  }
}
