// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.fridge

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.barcode.BarcodeScanResult
import com.android.sharemate.model.barcode.BarcodeScanner
import com.android.sharemate.model.chat.ChatRepository
import com.android.sharemate.model.chat.MessageType
import com.android.sharemate.model.chat.PendingFridgeActivity
import com.android.sharemate.model.chat.PendingFridgeActivityStore
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.image.FoodImageSelector
import com.android.sharemate.model.image.PersonalPhotoFiles
import com.android.sharemate.model.image.PersonalPhotoRepository
import com.android.sharemate.model.item.ExpirationDates
import com.android.sharemate.model.item.ExpirationInput
import com.android.sharemate.model.item.ExpiryStatus
import com.android.sharemate.model.item.ExpiryStatuses
import com.android.sharemate.model.item.FridgeSyncSnapshot
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemCategory
import com.android.sharemate.model.item.ItemEdit
import com.android.sharemate.model.item.ItemImage
import com.android.sharemate.model.item.ItemImageUpdateResult
import com.android.sharemate.model.item.ItemQuantity
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.item.ItemSharing
import com.android.sharemate.model.item.ItemStatus
import com.android.sharemate.model.item.ItemWrite
import com.android.sharemate.model.product.ProductCatalogRepository
import com.android.sharemate.model.product.ProductLookupResult
import com.android.sharemate.model.product.ProductLookupResult.Failure
import com.android.sharemate.model.product.ProductLookupResult.Reason
import com.android.sharemate.model.text.TextLimits
import com.android.sharemate.model.text.limitedTo
import com.android.sharemate.model.user.ExpirationPreferencesProvider
import com.android.sharemate.model.user.MemberNameRepository
import com.android.sharemate.model.user.PseudoRepository
import com.android.sharemate.ui.STATE_STOP_TIMEOUT_MILLIS
import com.android.sharemate.ui.navigation.AddItemMethod
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "FridgeViewModel"
private const val OWNER_NAMES_FAILURE_MESSAGE = "Owner names"
private const val ACTIVITY_SEND_FAILURE_MESSAGE = "Fridge activity message could not be sent"
private const val FRIDGE_SYNC_FAILURE_MESSAGE = "Unable to observe confirmed fridge items"
private const val PENDING_ACTIVITY_ID_SEPARATOR = ':'

/** An item owner offered by the Owner filter; [pseudo] is null when the owner has none. */
data class OwnerOption(val id: String, val pseudo: String?)

enum class FridgeSortOrder {
  EXPIRATION_DATE,
  NAME,
}

/**
 * Coordinates the fridge inventory and its presentation state.
 *
 * @param chatRepository Optional repository used to post confirmed shared-item activity.
 * @param activitySenderName Name displayed for activity messages sent by the current user.
 * @param pendingActivityStore Optional durable queue replayed after process restarts. A null store
 *   disables badge replay; it does not change normal status updates.
 */
class FridgeViewModel(
    private val itemRepository: ItemRepository,
    private val userId: String,
    private val householdId: String,
    private val foodImageSelector: FoodImageSelector,
    private val barcodeScanner: BarcodeScanner,
    private val productCatalogRepository: ProductCatalogRepository,
    photoStorage: PersonalPhotoRepository? = null,
    photoFiles: PersonalPhotoFiles? = null,
    private val expirationPreferences: ExpirationPreferencesProvider,
    pseudoRepository: PseudoRepository,
    private val fridgeType: FridgeType,
    private val today: () -> LocalDate = { LocalDate.now() },
    memberNameRepository: MemberNameRepository,
    currentUserDisplayName: String?,
    private val chatRepository: ChatRepository? = null,
    private val activitySenderName: String = currentUserDisplayName.orEmpty(),
    private val pendingActivityStore: PendingFridgeActivityStore? = null,
) : ViewModel() {
  private val supportsSharing = fridgeType == FridgeType.SHARED
  private val ownerNames =
      FridgeOwnerNames(
          memberNameRepository,
          pseudoRepository,
          householdId,
          userId,
          fridgeType,
          currentUserDisplayName,
          viewModelScope,
          { failure, cause -> Log.w(TAG, "$OWNER_NAMES_FAILURE_MESSAGE: $failure", cause) })
  private val mutableUiState =
      MutableStateFlow(
          FridgeUiState(
              fridgeType = fridgeType,
              isLoading = true,
              showSharingSelector = supportsSharing,
              currentUserId = userId,
              ownerNames = ownerNames.names.value))
  val personalPhotos =
      if (photoStorage != null && photoFiles != null)
          PersonalPhotoController(itemRepository, photoStorage, photoFiles, viewModelScope)
      else null

  val uiState = mutableUiState.asStateFlow()

  /** The user's "expiring soon" window; the default until their preferences load. */
  private val soonThresholdDays: Flow<Long> =
      expirationPreferences
          .observe(userId)
          .map { it.soonDays.toLong() }
          .onStart { emit(ExpirationDates.SOON_THRESHOLD_DAYS) }
          .distinctUntilChanged()

  /**
   * How close each loaded item, by id, is to its expiration date, according to the user's window.
   * The screen only displays it. It is worked out when the items or the window change, so it is not
   * refreshed by the day changing while the screen stays open.
   */
  val expiryStatuses: StateFlow<Map<String, ExpiryStatus>> =
      combine(mutableUiState.map { it.items }.distinctUntilChanged(), soonThresholdDays) {
              items,
              soonDays ->
            ExpiryStatuses.of(items, today(), soonDays)
          }
          .stateIn(
              viewModelScope, SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MILLIS), emptyMap())

  private val deletionOperations = mutableMapOf<String, Any>()
  private val confirmationHandlers = mutableMapOf<Any, DisposableHandle?>()
  private val latestStatusWrites = mutableMapOf<String, Any>()
  private val pendingActivityDispatches = mutableSetOf<String>()
  // Activities whose status write was issued by this ViewModel: their own confirmation handler
  // publishes or drops them, so replay must not touch them while the write is in flight. One that
  // failed transiently stays claimed and is retried by the next ViewModel's replay.
  private val inFlightActivityIds = mutableSetOf<String>()
  private var latestSync: FridgeSyncSnapshot? = null
  private var barcodeDialogGeneration = 0L
  private var barcodeScanJob: Job? = null

  private val sortOrderState = MutableStateFlow(FridgeSortOrder.EXPIRATION_DATE)
  private val categoryFilterState = MutableStateFlow<ItemCategory?>(null)
  private val ownerFilterState = MutableStateFlow<String?>(null)

  val sortOrder: StateFlow<FridgeSortOrder> = sortOrderState
  val selectedCategory: StateFlow<ItemCategory?> = categoryFilterState
  val selectedOwnerId: StateFlow<String?> = ownerFilterState

  /** The owners of the loaded items, with the pseudo to show for each. */
  val ownerOptions: StateFlow<List<OwnerOption>> =
      mutableUiState
          .map { state ->
            state.items
                .map(Item::ownerId)
                .filter(String::isNotBlank)
                .toSortedSet()
                .map { OwnerOption(it, state.ownerNameFor(it)) }
                .sortedWith(
                    compareBy<OwnerOption> { it.pseudo == null }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.pseudo ?: it.id })
          }
          .distinctUntilChanged()
          .stateIn(
              viewModelScope,
              SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MILLIS),
              emptyList())

  val visibleItems: StateFlow<List<Item>> =
      combine(
              mutableUiState,
              sortOrderState,
              categoryFilterState,
              ownerFilterState,
          ) { state, sortOrder, category, ownerId ->
            val filteredItems =
                state.items.filter { item ->
                  item.status == ItemStatus.ACTIVE &&
                      // An item without a category only shows under "All".
                      (category == null || item.category == category) &&
                      (ownerId == null || item.ownerId == ownerId)
                }

            when (sortOrder) {
              FridgeSortOrder.EXPIRATION_DATE ->
                  filteredItems.sortedWith(
                      compareBy<Item> { it.expirationDate == null }.thenBy { it.expirationDate })
              FridgeSortOrder.NAME ->
                  filteredItems.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            }
          }
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MILLIS),
              initialValue = emptyList(),
          )

  init {
    require(userId.isNotBlank()) { "A real user ID is required" }
    require(householdId.isNotBlank()) { "A household ID is required" }
    viewModelScope.launch {
      ownerNames.names.collect { names -> mutableUiState.update { it.copy(ownerNames = names) } }
    }
    observeFridgeSync()
    viewModelScope.launch {
      try {
        itemRepository.getFridgeItems(householdId, userId).collect { items ->
          mutableUiState.update { state ->
            val pending = state.pendingRemovalItem
            val stale =
                !state.isDeleting && pending != null && activeItem(items, pending.id) == null
            state.copy(
                items = items,
                pendingDeletionIds =
                    state.pendingDeletionIds.intersect(items.map { it.id }.toSet()),
                isLoading = false,
                loadFailed = false,
                pendingRemovalItem = if (stale) null else pending,
                removalFailed = if (stale) false else state.removalFailed)
          }
        }
      } catch (exception: CancellationException) {
        throw exception
      } catch (exception: Exception) {
        mutableUiState.update { it.copy(isLoading = false, loadFailed = true) }
      }
    }
  }

  /**
   * Replays queued activities from server-confirmed state on every sync snapshot. Items and their
   * confirmation come from the same snapshot, so a rejected write is never read as confirmed.
   */
  private fun observeFridgeSync() {
    viewModelScope.launch {
      try {
        itemRepository.observeFridgeSync(householdId, userId).collect { sync ->
          latestSync = sync
          viewModelScope.launch { flushPendingActivities() }
        }
      } catch (exception: CancellationException) {
        throw exception
      } catch (exception: Exception) {
        Log.w(TAG, FRIDGE_SYNC_FAILURE_MESSAGE, exception)
      }
    }
  }

  fun requestRemoval(itemId: String) {
    val state = uiState.value
    if (state.isDeleting ||
        state.isSaving ||
        state.isAddItemDialogOpen ||
        state.pendingRemovalItem != null ||
        itemId in state.pendingDeletionIds ||
        itemId in state.pendingStatusIds ||
        itemId.isBlank())
        return
    val item = activeItem(state.items, itemId) ?: return
    mutableUiState.update { it.copy(pendingRemovalItem = item, removalFailed = false) }
  }

  fun cancelRemoval() {
    if (uiState.value.isDeleting) return
    mutableUiState.update { it.copy(pendingRemovalItem = null, removalFailed = false) }
  }

  fun confirmRemoval() {
    val state = uiState.value
    if (state.isDeleting) return
    val item = state.pendingRemovalItem ?: return
    if (item.id in state.pendingDeletionIds) return
    val operation = Any().also { deletionOperations[item.id] = it }
    // Register before submission: a local snapshot can arrive before Queued is returned.
    mutableUiState.update {
      it.copy(
          isDeleting = true,
          removalFailed = false,
          pendingDeletionIds = it.pendingDeletionIds + item.id)
    }
    viewModelScope.launch {
      try {
        if (activeItem(uiState.value.items, item.id) == null) {
          releaseDeletion(item.id, operation, failed = false)
          mutableUiState.update { it.copy(pendingRemovalItem = null) }
          return@launch
        }
        val current = activeItem(uiState.value.items, item.id) ?: item
        personalPhotos?.prepareRemoval(current)
        when (val write = itemRepository.deleteItemQueued(item.id)) {
          is ItemWrite.Rejected -> {
            if (write.cause is CancellationException) throw write.cause
            releaseDeletion(item.id, operation, failed = true)
          }
          is ItemWrite.Queued -> {
            mutableUiState.update {
              it.copy(pendingRemovalItem = null, isDeleting = false, removalFailed = false)
            }
            trackConfirmation(
                write, onComplete = { deletionOperations.remove(item.id, operation) }) {
                  if (deletionOperations.remove(item.id, operation)) {
                    mutableUiState.update {
                      it.copy(
                          pendingDeletionIds = it.pendingDeletionIds - item.id,
                          removalSyncError = FridgeRemovalSyncError(item.name))
                    }
                  }
                }
          }
        }
      } catch (exception: CancellationException) {
        releaseDeletion(item.id, operation, failed = false)
        throw exception
      } catch (exception: Exception) {
        releaseDeletion(item.id, operation, failed = true)
      }
    }
  }

  private fun activeItem(items: List<Item>, itemId: String): Item? =
      items.firstOrNull {
        it.id == itemId && it.householdId == householdId && it.status == ItemStatus.ACTIVE
      }

  private fun releaseDeletion(itemId: String, operation: Any, failed: Boolean) {
    if (!deletionOperations.remove(itemId, operation)) return
    mutableUiState.update {
      it.copy(
          isDeleting = false,
          removalFailed = failed,
          pendingDeletionIds = it.pendingDeletionIds - itemId)
    }
  }

  fun dismissRemovalSyncError(error: FridgeRemovalSyncError) {
    mutableUiState.update {
      if (it.removalSyncError === error) it.copy(removalSyncError = null) else it
    }
  }

  fun setSortOrder(sortOrder: FridgeSortOrder) {
    sortOrderState.value = sortOrder
  }

  fun setItemStatus(itemId: String, status: ItemStatus) {
    val state = uiState.value
    if (status == ItemStatus.ACTIVE ||
        state.isSaving ||
        state.isDeleting ||
        state.isAddItemDialogOpen ||
        state.pendingRemovalItem != null ||
        itemId in state.pendingDeletionIds ||
        itemId in state.pendingStatusIds)
        return
    val item =
        state.items.firstOrNull {
          it.id == itemId && it.householdId == householdId && it.status == ItemStatus.ACTIVE
        } ?: return
    // Invalidate older confirmations as soon as this action is accepted, before launching it.
    val operation = Any()
    latestStatusWrites[itemId] = operation
    mutableUiState.update { it.copy(pendingStatusIds = it.pendingStatusIds + itemId) }
    val pendingActivity = pendingActivity(itemId, item.name, status, item.sharing)
    // Claimed before anything is persisted so a concurrent replay never drops this entry.
    pendingActivity?.let { inFlightActivityIds += it.id }
    var confirmationTracked = false
    var keepClaim = false
    viewModelScope.launch {
      try {
        if (uiState.value.items.none {
          it.id == itemId && it.householdId == householdId && it.status == ItemStatus.ACTIVE
        })
            return@launch
        pendingActivity?.let { persistPendingActivity(it) }
        when (val write = itemRepository.setItemStatus(itemId, status)) {
          is ItemWrite.Rejected -> {
            if (write.cause is CancellationException) throw write.cause
            pendingActivity?.let { removePendingActivity(it) }
            reportStatusError(item.name, false)
          }
          is ItemWrite.Queued -> {
            confirmationTracked = true
            trackConfirmation(
                write,
                onComplete = {
                  if (!keepClaim) pendingActivity?.let { inFlightActivityIds -= it.id }
                },
                onFailure = {
                  viewModelScope.launch { pendingActivity?.let { removePendingActivity(it) } }
                  if (latestStatusWrites[itemId] === operation) reportStatusError(item.name, true)
                },
                onSuccess = {
                  if (latestStatusWrites[itemId] === operation) {
                    keepClaim = postActivity(itemId, status)
                  }
                })
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        pendingActivity?.let { removePendingActivity(it) }
        reportStatusError(item.name, false)
      } finally {
        if (!confirmationTracked) pendingActivity?.let { inFlightActivityIds -= it.id }
        mutableUiState.update { it.copy(pendingStatusIds = it.pendingStatusIds - itemId) }
      }
    }
  }

  /** Publishes a confirmed action; returns true when its entry is kept for a later attempt. */
  private suspend fun postActivity(itemId: String, status: ItemStatus): Boolean {
    if (chatRepository == null) return false
    val item =
        uiState.value.items.firstOrNull { it.id == itemId && it.householdId == householdId }
            ?: run {
              removePendingActivity(buildPendingActivity(itemId, "", status))
              return false
            }
    if (fridgeType != FridgeType.SHARED) return false
    val expectedStatus = if (status == ItemStatus.EATEN) ItemStatus.EATEN else ItemStatus.DISCARDED
    if (item.status != expectedStatus) {
      removePendingActivity(buildPendingActivity(itemId, item.name, status))
      return false
    }
    if (item.sharing != ItemSharing.SHARED) {
      removePendingActivity(buildPendingActivity(itemId, item.name, status))
      return false
    }
    val pending = pendingActivity(itemId, item.name, status, item.sharing) ?: return false
    return sendPendingActivity(pending, item.name)
  }

  private fun pendingActivity(
      itemId: String,
      itemName: String,
      status: ItemStatus,
      sharing: ItemSharing,
  ): PendingFridgeActivity? {
    if (fridgeType != FridgeType.SHARED ||
        sharing != ItemSharing.SHARED ||
        itemId.isBlank() ||
        itemName.isBlank()) {
      return null
    }
    return buildPendingActivity(itemId, itemName.trim(), status)
  }

  private fun buildPendingActivity(
      itemId: String,
      itemName: String,
      status: ItemStatus,
  ): PendingFridgeActivity {
    val type = if (status == ItemStatus.EATEN) MessageType.EATEN else MessageType.THROWN
    return PendingFridgeActivity(
        id = "$itemId$PENDING_ACTIVITY_ID_SEPARATOR${type.name}",
        householdId = householdId,
        userId = userId,
        type = type,
        itemName = itemName,
    )
  }

  private suspend fun persistPendingActivity(activity: PendingFridgeActivity) {
    val store = pendingActivityStore ?: return
    try {
      store.save(activity)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      Log.w(TAG, "Unable to persist pending fridge activity", failure)
    }
  }

  private suspend fun removePendingActivity(activity: PendingFridgeActivity) {
    val store = pendingActivityStore ?: return
    try {
      store.remove(activity)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      Log.w(TAG, "Unable to remove pending fridge activity", failure)
    }
  }

  private suspend fun flushPendingActivities() {
    val store = pendingActivityStore ?: return
    if (fridgeType != FridgeType.SHARED) return
    val sync = latestSync ?: return
    try {
      store
          .list(householdId, userId)
          .filter { it.id !in inFlightActivityIds }
          .forEach { replayPendingActivity(it, sync) }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      Log.w(TAG, "Unable to load pending fridge activities", failure)
    }
  }

  /**
   * Publishes [activity] only if [sync] shows its status confirmed by the server. An item whose
   * write is still unconfirmed keeps its entry; any other state means the action did not happen.
   */
  private suspend fun replayPendingActivity(
      activity: PendingFridgeActivity,
      sync: FridgeSyncSnapshot,
  ) {
    val itemId = activity.id.substringBefore(PENDING_ACTIVITY_ID_SEPARATOR)
    if (itemId in sync.pendingWriteIds) return
    val expectedStatus =
        when (activity.type) {
          MessageType.EATEN -> ItemStatus.EATEN
          MessageType.THROWN -> ItemStatus.DISCARDED
          else -> null
        }
    val item =
        expectedStatus?.let { status ->
          sync.confirmedItems.firstOrNull {
            it.id == itemId &&
                it.householdId == householdId &&
                it.status == status &&
                it.sharing == ItemSharing.SHARED
          }
        }
    if (item == null) {
      removePendingActivity(activity)
      return
    }
    sendPendingActivity(activity, item.name)
  }

  /** Sends [activity]; returns true when a transient failure kept its entry for a later attempt. */
  private suspend fun sendPendingActivity(
      activity: PendingFridgeActivity,
      itemName: String,
  ): Boolean {
    val repository = chatRepository ?: return false
    if (activity.id in pendingActivityDispatches) return false
    pendingActivityDispatches += activity.id
    return try {
      // A null result is permanent: drop the entry. A thrown failure is transient: keep it.
      if (repository.sendActivity(
          activity.householdId,
          activity.userId,
          activitySenderName,
          activity.type,
          itemName,
          activityId = activity.id,
      ) == null) {
        Log.w(TAG, ACTIVITY_SEND_FAILURE_MESSAGE)
      }
      removePendingActivity(activity)
      false
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      Log.w(TAG, ACTIVITY_SEND_FAILURE_MESSAGE, failure)
      true
    } finally {
      pendingActivityDispatches -= activity.id
    }
  }

  private fun reportStatusError(name: String, syncFailure: Boolean) {
    mutableUiState.update { it.copy(statusError = FridgeStatusError(name, syncFailure)) }
  }

  fun dismissStatusError(error: FridgeStatusError) {
    mutableUiState.update { if (it.statusError === error) it.copy(statusError = null) else it }
  }

  /** Shows only the items of [category], or every item when it is null ("All"). */
  fun setCategoryFilter(category: ItemCategory?) {
    categoryFilterState.value = category
  }

  fun setOwnerFilter(ownerId: String?) {
    ownerFilterState.value = ownerId.normalizedFilter()
  }

  /**
   * Opens the add-item form, unless another form or action is in progress.
   *
   * @param fromAddButton The option of the add button of the bottom bar that opens the form, or
   *   null for the fridge's own button. [AddItemMethod.BARCODE] opens the barcode photo right away.
   */
  fun openAddItemDialog(fromAddButton: AddItemMethod? = null) {
    if (uiState.value.isSaving ||
        uiState.value.isAddItemDialogOpen ||
        uiState.value.pendingRemovalItem != null ||
        uiState.value.isDeleting)
        return
    barcodeDialogGeneration++
    cancelBarcodeScan()
    mutableUiState.update {
      it.resetItemFields()
          .copy(
              isAddItemDialogOpen = true,
              addButtonMethod = fromAddButton,
              scanBarcodeOnOpen = fromAddButton == AddItemMethod.BARCODE,
              closeOnBarcodeCancel = fromAddButton == AddItemMethod.BARCODE,
              canChangeSharing = supportsSharing,
              editingItemId = null,
              isScanningBarcode = false,
              barcodeValue = null,
              barcodeScanFailed = false,
              barcodeLookupError = null,
              formError = null)
    }
  }

  /** Called by the add-item form once it opened the barcode photo asked by [openAddItemDialog]. */
  fun barcodeScanOpened() {
    mutableUiState.update { it.copy(scanBarcodeOnOpen = false) }
  }

  fun dismissAddItemDialog() {
    if (uiState.value.isSaving) return
    personalPhotos?.discardDraft()
    barcodeDialogGeneration++
    cancelBarcodeScan()
    mutableUiState.update {
      it.resetItemFields()
          .copy(
              isAddItemDialogOpen = false,
              editingItemId = null,
              isScanningBarcode = false,
              barcodeValue = null,
              barcodeScanFailed = false,
              barcodeLookupError = null,
              formError = null)
    }
  }

  /** Called once the app opened the fridge for [FridgeUiState.addButtonItemSaved]. */
  fun addButtonSaveHandled() {
    mutableUiState.update { it.copy(addButtonItemSaved = false) }
  }

  /**
   * Decodes an image and looks up its barcode in the product catalog.
   *
   * The caller receives the catalog result directly and decides how to apply any returned details
   * to its form. A scan that finds no barcode is reported as [Reason.INVALID_BARCODE]; unexpected
   * scanner failures use [Reason.SCANNER_FAILURE]. Coroutine cancellation is propagated.
   */
  suspend fun lookupProductFromImage(imageUri: String): ProductLookupResult {
    if (imageUri.isBlank()) return Failure(Reason.INVALID_BARCODE)
    val scanResult =
        try {
          barcodeScanner.scan(imageUri)
        } catch (exception: CancellationException) {
          throw exception
        } catch (exception: Exception) {
          return Failure(Reason.SCANNER_FAILURE)
        }
    val barcode =
        when (scanResult) {
          is BarcodeScanResult.Found -> scanResult.value.trim().takeIf(String::isNotEmpty)
          BarcodeScanResult.NotFound -> null
        } ?: return Failure(Reason.INVALID_BARCODE)
    return productCatalogRepository.lookupByBarcode(barcode)
  }

  /**
   * Suspends until the image is no longer needed; never detach this work from photo confirmation.
   */
  suspend fun confirmBarcodeImage(imageUri: String): Unit = coroutineScope {
    val initial = uiState.value
    if (!initial.isAddItemDialogOpen ||
        initial.editingItemId != null ||
        initial.isSaving ||
        initial.isScanningBarcode) {
      throw CancellationException("Photo confirmation is no longer available")
    }
    val generation = barcodeDialogGeneration
    val operation = currentCoroutineContext()[Job]
    barcodeScanJob = operation
    mutableUiState.update {
      it.copy(
          closeOnBarcodeCancel = false,
          isScanningBarcode = true,
          barcodeLookupError = null,
          barcodeScanFailed = false,
          barcodeValue = null)
    }
    try {
      val result =
          try {
            lookupProductFromImage(imageUri)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            Failure(Reason.NETWORK)
          }
      currentCoroutineContext().ensureActive()
      if (generation != barcodeDialogGeneration || !uiState.value.isAddItemDialogOpen) {
        throw CancellationException("The item form was closed")
      }
      val error =
          when (result) {
            is ProductLookupResult.Found -> {
              // The item schema has one editable name, so retain both catalog details there.
              val name =
                  listOfNotNull(result.product.name, result.product.brand)
                      .map(String::trim)
                      .filter(String::isNotEmpty)
                      .distinct()
                      .joinToString(" — ")
              if (name.isEmpty()) {
                updateBarcodeScanState(generation) {
                  it.copy(barcodeValue = result.product.barcode)
                }
                BarcodeLookupError.NO_DETAILS
              } else {
                updateBarcodeScanState(generation) {
                  it.copy(
                      itemName =
                          if (it.itemName == initial.itemName) name.limitedTo(TextLimits.ITEM_NAME)
                          else it.itemName,
                      barcodeValue = result.product.barcode,
                      formError =
                          if (it.formError == FridgeFormError.NAME_REQUIRED) null else it.formError)
                }
                null
              }
            }
            ProductLookupResult.NotFound -> BarcodeLookupError.NOT_FOUND
            is Failure ->
                when (result.reason) {
                  Reason.INVALID_BARCODE -> BarcodeLookupError.UNREADABLE
                  Reason.SCANNER_FAILURE -> BarcodeLookupError.SCANNER_FAILED
                  else -> BarcodeLookupError.LOOKUP_FAILED
                }
          }
      if (error != null) {
        updateBarcodeScanState(generation) { it.copy(barcodeLookupError = error) }
        throw BarcodeLookupException(error)
      }
    } finally {
      updateBarcodeScanState(generation) { it.copy(isScanningBarcode = false) }
      if (barcodeScanJob === operation) barcodeScanJob = null
    }
  }

  fun scanBarcodeImage(imageUri: String) {
    val state = uiState.value
    if (!state.isAddItemDialogOpen ||
        state.editingItemId != null ||
        state.isSaving ||
        state.isScanningBarcode)
        return
    val generation = barcodeDialogGeneration
    mutableUiState.update {
      it.copy(isScanningBarcode = true, barcodeValue = null, barcodeScanFailed = false)
    }
    val scanJob =
        viewModelScope.launch(start = CoroutineStart.LAZY) {
          try {
            when (val result = barcodeScanner.scan(imageUri)) {
              is BarcodeScanResult.Found -> {
                val value = result.value.trim()
                updateBarcodeScanState(generation) {
                  it.copy(
                      isScanningBarcode = false,
                      barcodeValue = value.takeIf(String::isNotEmpty),
                      barcodeScanFailed = value.isEmpty())
                }
              }
              BarcodeScanResult.NotFound ->
                  updateBarcodeScanState(generation) {
                    it.copy(isScanningBarcode = false, barcodeScanFailed = true)
                  }
            }
          } catch (exception: CancellationException) {
            updateBarcodeScanState(generation) { it.copy(isScanningBarcode = false) }
            throw exception
          } catch (exception: Exception) {
            updateBarcodeScanState(generation) {
              it.copy(isScanningBarcode = false, barcodeValue = null, barcodeScanFailed = true)
            }
          } finally {
            if (barcodeScanJob === currentCoroutineContext()[Job]) barcodeScanJob = null
          }
        }
    barcodeScanJob = scanJob
    scanJob.start()
  }

  private fun cancelBarcodeScan() {
    barcodeScanJob?.cancel()
    barcodeScanJob = null
  }

  private fun updateBarcodeScanState(
      generation: Long,
      transform: (FridgeUiState) -> FridgeUiState,
  ) {
    if (barcodeDialogGeneration != generation) return
    mutableUiState.update { state ->
      if (state.isAddItemDialogOpen && state.editingItemId == null) transform(state) else state
    }
  }

  fun openEditItemDialog(itemId: String) {
    val state = uiState.value
    if (state.isSaving ||
        state.isAddItemDialogOpen ||
        state.pendingRemovalItem != null ||
        state.isDeleting ||
        itemId in state.pendingDeletionIds ||
        itemId in state.pendingStatusIds)
        return
    val item =
        state.items.firstOrNull {
          it.id == itemId && it.status == ItemStatus.ACTIVE && it.householdId == householdId
        } ?: return
    mutableUiState.update {
      it.copy(
          isAddItemDialogOpen = true,
          editingItemId = item.id,
          itemSharing = item.sharing,
          originalItemSharing = item.sharing,
          canChangeSharing = supportsSharing && item.ownerId == userId,
          itemName = item.name,
          itemQuantity = item.quantity.toString(),
          itemCategory = item.category,
          originalItemCategory = item.category,
          expirationDateInput = ExpirationDates.formatInput(item.expirationDate),
          formError = null)
    }
  }

  /** Updates the quantity, cut to [ItemQuantity.MAX_INPUT_LENGTH] characters. */
  fun updateQuantity(quantity: String) = updateDraft {
    it.copy(itemQuantity = quantity.limitedTo(ItemQuantity.MAX_INPUT_LENGTH), formError = null)
  }

  /** Updates the name, cut to [TextLimits.ITEM_NAME] characters so a paste cannot exceed it. */
  fun updateName(name: String) = updateDraft {
    it.copy(itemName = name.limitedTo(TextLimits.ITEM_NAME), formError = null)
  }

  /** Picks the category of the item in the form; null is "No category". */
  fun updateCategory(category: ItemCategory?) = updateDraft {
    it.copy(itemCategory = category, formError = null)
  }

  fun updateExpirationDate(date: String) = updateDraft {
    it.copy(expirationDateInput = date, formError = null)
  }

  fun updateSharing(sharing: ItemSharing) {
    val state = uiState.value
    if (!supportsSharing || !state.canChangeSharing) return
    val itemId = state.editingItemId
    if (itemId != null && activeItem(state.items, itemId)?.ownerId != userId) return
    updateDraft { it.copy(itemSharing = sharing, formError = null) }
  }

  private fun updateDraft(update: (FridgeUiState) -> FridgeUiState) {
    if (!uiState.value.isAddItemDialogOpen || uiState.value.isSaving) return
    mutableUiState.update(update)
  }

  fun saveItem() {
    if (personalPhotos?.draft?.value?.importing == true) return
    val state = uiState.value
    if (!state.isAddItemDialogOpen || state.isSaving || state.isScanningBarcode) return
    val name = state.itemName.trim()
    if (name.isEmpty()) {
      mutableUiState.update { it.copy(formError = FridgeFormError.NAME_REQUIRED) }
      return
    }
    validateItemTexts(name)?.let { error ->
      mutableUiState.update { it.copy(formError = error) }
      return
    }
    val expirationDate =
        when (val parsed = ExpirationDates.parse(state.expirationDateInput)) {
          ExpirationInput.Empty -> null
          is ExpirationInput.Valid -> parsed.date
          ExpirationInput.Invalid -> {
            mutableUiState.update { it.copy(formError = FridgeFormError.INVALID_EXPIRATION_DATE) }
            return
          }
        }
    val quantity = ItemQuantity.parse(state.itemQuantity)
    if (quantity == null) {
      mutableUiState.update { it.copy(formError = FridgeFormError.INVALID_QUANTITY) }
      return
    }
    if (state.editingItemId != null) {
      saveEdit(
          state.editingItemId,
          ItemEdit(
              name,
              quantity,
              state.itemCategory,
              expirationDate,
              // Compare to the opened draft, not live snapshots: untouched sharing must not
              // overwrite a change made by another client while this dialog was open.
              sharing =
                  state.itemSharing.takeIf { supportsSharing && it != state.originalItemSharing },
              // An untouched category keeps its stored value, even an older free-text one.
              updatesCategory = state.itemCategory != state.originalItemCategory))
      return
    }
    val draftItem =
        Item(
            name = name,
            ownerId = userId,
            householdId = householdId,
            quantity = quantity,
            expirationDate = expirationDate,
            category = state.itemCategory,
            sharing = if (supportsSharing) state.itemSharing else ItemSharing.PERSONAL)
    mutableUiState.update { it.copy(isSaving = true, formError = null) }
    viewModelScope.launch {
      try {
        currentCoroutineContext().ensureActive()
        val itemId = itemRepository.addItem(draftItem)
        if (itemId.isNullOrBlank()) {
          mutableUiState.update {
            it.copy(isSaving = false, formError = FridgeFormError.SAVE_FAILED)
          }
        } else {
          mutableUiState.update {
            it.resetItemFields()
                .copy(
                    isSaving = false,
                    isAddItemDialogOpen = false,
                    // Kept here, as the form may close while its host is being recreated.
                    addButtonItemSaved = it.addButtonMethod != null,
                    barcodeValue = null,
                    barcodeScanFailed = false,
                    formError = null)
          }
          val photos = personalPhotos
          val photo = photos?.takeDraft()
          if (photos != null && photo != null) photos.attachCreated(itemId, name, photo)
          else enrichItemImage(itemId, name)
        }
      } catch (exception: CancellationException) {
        mutableUiState.update { it.copy(isSaving = false) }
        throw exception
      } catch (exception: Exception) {
        mutableUiState.update { it.copy(isSaving = false, formError = FridgeFormError.SAVE_FAILED) }
      }
    }
  }

  private fun enrichItemImage(itemId: String, savedName: String) {
    // The navigation entry retains this scope across tabs. Session teardown clears it, cancelling
    // all lookups (and their network calls). Nothing resumes after process death.
    viewModelScope.launch {
      try {
        val image =
            ItemImage.fromSelection(foodImageSelector.selectAutomatic(savedName)) ?: return@launch
        currentCoroutineContext().ensureActive()
        // Inventory flows own the image update. Never copy a stale item into UI state or retry
        // creation, including when the transaction ignores the result or fails.
        when (val result = itemRepository.updateItemImageIfAbsent(itemId, savedName, image)) {
          ItemImageUpdateResult.Updated,
          ItemImageUpdateResult.Ignored -> Unit
          is ItemImageUpdateResult.Error ->
              if (result.cause is CancellationException) throw result.cause
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        // Enrichment is optional; an already successful creation stays successful.
      }
    }
  }

  private fun saveEdit(itemId: String, edit: ItemEdit) {
    val state = uiState.value
    val item = activeItem(state.items, itemId)
    if (itemId in state.pendingDeletionIds ||
        item == null ||
        (edit.sharing != null && (!supportsSharing || item.ownerId != userId))) {
      mutableUiState.update { it.copy(formError = FridgeFormError.SAVE_FAILED) }
      return
    }
    mutableUiState.update { it.copy(isSaving = true, formError = null) }
    viewModelScope.launch {
      try {
        if (edit.sharing != null && activeItem(uiState.value.items, itemId)?.ownerId != userId) {
          mutableUiState.update {
            it.copy(isSaving = false, formError = FridgeFormError.SAVE_FAILED)
          }
          return@launch
        }
        when (val write = itemRepository.updateItem(itemId, edit)) {
          is ItemWrite.Rejected -> {
            if (write.cause is CancellationException) throw write.cause
            mutableUiState.update {
              it.copy(isSaving = false, formError = FridgeFormError.SAVE_FAILED)
            }
          }
          is ItemWrite.Queued -> {
            // Snapshots own local application and rollback; submission is not server approval.
            mutableUiState.update {
              it.resetItemFields()
                  .copy(isSaving = false, isAddItemDialogOpen = false, editingItemId = null)
            }
            trackConfirmation(write) { reportEditSyncError(edit.name) }
          }
        }
      } catch (cancelled: CancellationException) {
        mutableUiState.update { it.copy(isSaving = false) }
        throw cancelled
      } catch (_: Exception) {
        mutableUiState.update { it.copy(isSaving = false, formError = FridgeFormError.SAVE_FAILED) }
      }
    }
  }

  // Observe each queued write independently without a waiting job while offline.
  private fun trackConfirmation(
      write: ItemWrite.Queued,
      onComplete: () -> Unit = {},
      onSuccess: suspend () -> Unit = {},
      onFailure: () -> Unit,
  ) {
    val operation = Any()
    confirmationHandlers[operation] = null
    val handler =
        write.confirmation.invokeOnCompletion { cause ->
          viewModelScope.launch {
            confirmationHandlers.remove(operation)?.dispose()
            if (cause !is CancellationException) {
              val result =
                  try {
                    write.confirmation.await()
                  } catch (cancelled: CancellationException) {
                    throw cancelled
                  } catch (_: Exception) {
                    null
                  }
              if (result == null || result.isFailure) {
                onFailure()
              } else {
                onSuccess()
              }
            }
            onComplete()
          }
        }
    if (operation in confirmationHandlers) confirmationHandlers[operation] = handler
    else handler.dispose()
  }

  override fun onCleared() {
    personalPhotos?.discardDraft()
    barcodeDialogGeneration++
    cancelBarcodeScan()
    confirmationHandlers.values.forEach { it?.dispose() }
    confirmationHandlers.clear()
    latestStatusWrites.clear()
    barcodeScanner.close()
    mutableUiState.update { it.copy(pendingStatusIds = emptySet()) }
  }

  private fun reportEditSyncError(name: String) {
    mutableUiState.update { it.copy(editSyncError = FridgeEditSyncError(name)) }
  }

  fun dismissEditSyncError(error: FridgeEditSyncError) {
    mutableUiState.update { if (it.editSyncError === error) it.copy(editSyncError = null) else it }
  }

  private fun String?.normalizedFilter(): String? = this?.trim()?.takeIf(String::isNotEmpty)
}
