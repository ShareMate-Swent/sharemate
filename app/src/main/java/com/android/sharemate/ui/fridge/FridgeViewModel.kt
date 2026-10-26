// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.fridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.barcode.BarcodeScanResult
import com.android.sharemate.model.barcode.BarcodeScanner
import com.android.sharemate.model.image.FoodImageSelector
import com.android.sharemate.model.image.FoodImageTimeouts.IMAGE_LOOKUP_TIMEOUT_MS
import com.android.sharemate.model.item.ExpirationDates
import com.android.sharemate.model.item.ExpirationInput
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemEdit
import com.android.sharemate.model.item.ItemImage
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.item.ItemStatus
import com.android.sharemate.model.item.ItemWrite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class FridgeSortOrder {
  EXPIRATION_DATE,
  NAME,
}

class FridgeViewModel(
    private val itemRepository: ItemRepository,
    private val userId: String,
    private val householdId: String?,
    private val foodImageSelector: FoodImageSelector,
    private val barcodeScanner: BarcodeScanner
) : ViewModel() {
  private val mutableUiState = MutableStateFlow(FridgeUiState(isLoading = true))
  val uiState = mutableUiState.asStateFlow()
  private val deletionOperations = mutableMapOf<String, Any>()
  private val confirmationHandlers = mutableMapOf<Any, DisposableHandle?>()
  private val latestStatusWrites = mutableMapOf<String, Any>()
  private var barcodeDialogGeneration = 0L

  private val sortOrderState = MutableStateFlow(FridgeSortOrder.EXPIRATION_DATE)
  private val categoryFilterState = MutableStateFlow<String?>(null)
  private val ownerFilterState = MutableStateFlow<String?>(null)

  val sortOrder: StateFlow<FridgeSortOrder> = sortOrderState
  val selectedCategory: StateFlow<String?> = categoryFilterState
  val selectedOwnerId: StateFlow<String?> = ownerFilterState

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
                      (category == null ||
                          item.category?.trim()?.equals(category, ignoreCase = true) == true) &&
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
              started = SharingStarted.WhileSubscribed(5_000),
              initialValue = emptyList(),
          )

  init {
    require(userId.isNotBlank()) { "A real user ID is required" }
    require(householdId == null || householdId.isNotBlank()) {
      "A household ID must be non-blank when provided"
    }
    viewModelScope.launch {
      try {
        val inventory =
            if (householdId == null) itemRepository.getPrivateItems(userId)
            else itemRepository.getSharedItems(householdId)
        inventory.collect { items ->
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
    viewModelScope.launch {
      try {
        if (uiState.value.items.none {
          it.id == itemId && it.householdId == householdId && it.status == ItemStatus.ACTIVE
        })
            return@launch
        when (val write = itemRepository.setItemStatus(itemId, status)) {
          is ItemWrite.Rejected -> {
            if (write.cause is CancellationException) throw write.cause
            reportStatusError(item.name, false)
          }
          is ItemWrite.Queued ->
              trackConfirmation(write) {
                if (latestStatusWrites[itemId] === operation) reportStatusError(item.name, true)
              }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        reportStatusError(item.name, false)
      } finally {
        mutableUiState.update { it.copy(pendingStatusIds = it.pendingStatusIds - itemId) }
      }
    }
  }

  private fun reportStatusError(name: String, syncFailure: Boolean) {
    mutableUiState.update { it.copy(statusError = FridgeStatusError(name, syncFailure)) }
  }

  fun dismissStatusError(error: FridgeStatusError) {
    mutableUiState.update { if (it.statusError === error) it.copy(statusError = null) else it }
  }

  fun setCategoryFilter(category: String?) {
    categoryFilterState.value = category.normalizedFilter()
  }

  fun setOwnerFilter(ownerId: String?) {
    ownerFilterState.value = ownerId.normalizedFilter()
  }

  fun openAddItemDialog() {
    if (uiState.value.isSaving ||
        uiState.value.isAddItemDialogOpen ||
        uiState.value.pendingRemovalItem != null ||
        uiState.value.isDeleting)
        return
    barcodeDialogGeneration++
    mutableUiState.update {
      it.copy(
          isAddItemDialogOpen = true,
          editingItemId = null,
          itemQuantity = "1",
          itemName = "",
          itemCategory = "",
          expirationDateInput = "",
          isScanningBarcode = false,
          barcodeValue = null,
          barcodeScanFailed = false,
          formError = null)
    }
  }

  fun dismissAddItemDialog() {
    if (uiState.value.isSaving) return
    barcodeDialogGeneration++
    mutableUiState.update {
      it.copy(
          isAddItemDialogOpen = false,
          editingItemId = null,
          itemQuantity = "1",
          itemName = "",
          itemCategory = "",
          expirationDateInput = "",
          isScanningBarcode = false,
          barcodeValue = null,
          barcodeScanFailed = false,
          formError = null)
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
    viewModelScope.launch {
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
      }
    }
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
          itemName = item.name,
          itemQuantity = item.quantity.toString(),
          itemCategory = item.category.orEmpty(),
          expirationDateInput = ExpirationDates.formatInput(item.expirationDate),
          formError = null)
    }
  }

  fun updateQuantity(quantity: String) = updateDraft {
    it.copy(itemQuantity = quantity, formError = null)
  }

  fun updateName(name: String) = updateDraft { it.copy(itemName = name, formError = null) }

  fun updateCategory(category: String) = updateDraft {
    it.copy(itemCategory = category, formError = null)
  }

  fun updateExpirationDate(date: String) = updateDraft {
    it.copy(expirationDateInput = date, formError = null)
  }

  private fun updateDraft(update: (FridgeUiState) -> FridgeUiState) {
    if (!uiState.value.isAddItemDialogOpen || uiState.value.isSaving) return
    mutableUiState.update(update)
  }

  fun saveItem() {
    val state = uiState.value
    if (!state.isAddItemDialogOpen || state.isSaving) return
    val name = state.itemName.trim()
    if (name.isEmpty()) {
      mutableUiState.update { it.copy(formError = FridgeFormError.NAME_REQUIRED) }
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
    val quantity = state.itemQuantity.trim().toIntOrNull()
    if (quantity == null || quantity <= 0) {
      mutableUiState.update { it.copy(formError = FridgeFormError.INVALID_QUANTITY) }
      return
    }
    if (state.editingItemId != null) {
      saveEdit(
          state.editingItemId,
          ItemEdit(
              name, quantity, state.itemCategory.trim().takeIf(String::isNotEmpty), expirationDate))
      return
    }
    val draftItem =
        Item(
            name = name,
            ownerId = userId,
            householdId = householdId,
            quantity = quantity,
            expirationDate = expirationDate,
            category = state.itemCategory.trim().takeIf { it.isNotEmpty() })
    mutableUiState.update { it.copy(isSaving = true, formError = null) }
    viewModelScope.launch {
      try {
        val image =
            try {
              // The optional lookup has its own budget; saving below is not timed out.
              withTimeoutOrNull(IMAGE_LOOKUP_TIMEOUT_MS) {
                ItemImage.fromSelection(
                    foodImageSelector.select(name, personalPhotoReference = null))
              }
            } catch (exception: CancellationException) {
              throw exception
            } catch (exception: Exception) {
              // Image lookup is optional and must not prevent saving the food.
              null
            }
        currentCoroutineContext().ensureActive()
        val item = draftItem.copy(image = image)
        val itemId = itemRepository.addItem(item)
        if (itemId.isNullOrBlank()) {
          mutableUiState.update {
            it.copy(isSaving = false, formError = FridgeFormError.SAVE_FAILED)
          }
        } else {
          mutableUiState.update {
            it.copy(
                items =
                    if (it.items.any { saved -> saved.id == itemId }) it.items
                    else it.items + item.copy(id = itemId),
                isSaving = false,
                isAddItemDialogOpen = false,
                itemQuantity = "1",
                itemName = "",
                itemCategory = "",
                expirationDateInput = "",
                barcodeValue = null,
                barcodeScanFailed = false,
                formError = null)
          }
        }
      } catch (exception: CancellationException) {
        mutableUiState.update { it.copy(isSaving = false) }
        throw exception
      } catch (exception: Exception) {
        mutableUiState.update { it.copy(isSaving = false, formError = FridgeFormError.SAVE_FAILED) }
      }
    }
  }

  private fun saveEdit(itemId: String, edit: ItemEdit) {
    val state = uiState.value
    if (itemId in state.pendingDeletionIds || activeItem(state.items, itemId) == null) {
      mutableUiState.update { it.copy(formError = FridgeFormError.SAVE_FAILED) }
      return
    }
    mutableUiState.update { it.copy(isSaving = true, formError = null) }
    viewModelScope.launch {
      try {
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
              it.copy(
                  isSaving = false,
                  isAddItemDialogOpen = false,
                  editingItemId = null,
                  itemName = "",
                  itemQuantity = "1",
                  itemCategory = "",
                  expirationDateInput = "")
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
      onFailure: () -> Unit,
  ) {
    val operation = Any()
    confirmationHandlers[operation] = null
    val handler =
        write.confirmation.invokeOnCompletion { cause ->
          viewModelScope.launch {
            confirmationHandlers.remove(operation)?.dispose()
            if (cause !is CancellationException) {
              try {
                if (write.confirmation.await().isFailure) onFailure()
              } catch (cancelled: CancellationException) {
                throw cancelled
              } catch (_: Exception) {
                onFailure()
              }
            }
            onComplete()
          }
        }
    if (operation in confirmationHandlers) confirmationHandlers[operation] = handler
    else handler.dispose()
  }

  override fun onCleared() {
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
