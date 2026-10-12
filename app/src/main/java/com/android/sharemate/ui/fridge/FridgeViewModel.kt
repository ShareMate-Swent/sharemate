// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.fridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemEdit
import com.android.sharemate.model.item.ItemRepository
import com.android.sharemate.model.item.ItemStatus
import com.android.sharemate.model.item.ItemWrite
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FridgeSortOrder {
  EXPIRATION_DATE,
  NAME,
}

class FridgeViewModel(
    private val itemRepository: ItemRepository,
    private val userId: String,
    private val householdId: String,
) : ViewModel() {
  private val mutableUiState = MutableStateFlow(FridgeUiState(isLoading = true))
  val uiState = mutableUiState.asStateFlow()
  private val confirmationHandlers = mutableMapOf<Any, DisposableHandle?>()

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
    require(householdId.isNotBlank()) { "A real household ID is required" }
    viewModelScope.launch {
      try {
        itemRepository.getSharedItems(householdId).collect { items ->
          mutableUiState.update { it.copy(items = items, isLoading = false, loadFailed = false) }
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
        itemId.isBlank())
        return
    val item = state.items.firstOrNull { it.id == itemId } ?: return
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
    if (state.items.none { it.id == item.id }) {
      cancelRemoval()
      return
    }
    mutableUiState.update { it.copy(isDeleting = true, removalFailed = false) }
    viewModelScope.launch {
      try {
        if (itemRepository.deleteItem(item.id)) {
          mutableUiState.update {
            it.copy(
                items = it.items.filterNot { saved -> saved.id == item.id },
                pendingRemovalItem = null,
                isDeleting = false,
                removalFailed = false)
          }
        } else {
          mutableUiState.update { it.copy(isDeleting = false, removalFailed = true) }
        }
      } catch (exception: CancellationException) {
        mutableUiState.update { it.copy(isDeleting = false) }
        throw exception
      } catch (exception: Exception) {
        mutableUiState.update { it.copy(isDeleting = false, removalFailed = true) }
      }
    }
  }

  fun setSortOrder(sortOrder: FridgeSortOrder) {
    sortOrderState.value = sortOrder
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
    mutableUiState.update {
      it.copy(
          isAddItemDialogOpen = true,
          editingItemId = null,
          itemQuantity = "1",
          itemName = "",
          itemCategory = "",
          expirationDateInput = "",
          formError = null)
    }
  }

  fun dismissAddItemDialog() {
    if (uiState.value.isSaving) return
    mutableUiState.update {
      it.copy(
          isAddItemDialogOpen = false,
          editingItemId = null,
          itemQuantity = "1",
          itemName = "",
          itemCategory = "",
          expirationDateInput = "",
          formError = null)
    }
  }

  fun openEditItemDialog(itemId: String) {
    val state = uiState.value
    if (state.isSaving ||
        state.isAddItemDialogOpen ||
        state.pendingRemovalItem != null ||
        state.isDeleting)
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
          expirationDateInput =
              item.expirationDate
                  ?.let { it.toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString() }
                  .orEmpty(),
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
        try {
          val input = state.expirationDateInput.trim()
          if (input.isEmpty()) null
          else {
            if (!input.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) {
              mutableUiState.update { it.copy(formError = FridgeFormError.INVALID_EXPIRATION_DATE) }
              return
            }
            Date.from(LocalDate.parse(input).atStartOfDay(ZoneOffset.UTC).toInstant())
          }
        } catch (exception: DateTimeParseException) {
          mutableUiState.update { it.copy(formError = FridgeFormError.INVALID_EXPIRATION_DATE) }
          return
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
    val item =
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
    if (state.items.none {
      it.id == itemId && it.householdId == householdId && it.status == ItemStatus.ACTIVE
    }) {
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
            // No coroutine waits indefinitely offline. Each callback owns its write;
            // completed confirmations only report errors, never alter drafts or items.
            val operation = Any()
            confirmationHandlers[operation] = null
            val handler =
                write.confirmation.invokeOnCompletion { cause ->
                  viewModelScope.launch {
                    confirmationHandlers.remove(operation)?.dispose()
                    if (cause !is CancellationException) {
                      try {
                        if (write.confirmation.await().isFailure) reportEditSyncError(edit.name)
                      } catch (cancelled: CancellationException) {
                        throw cancelled
                      } catch (_: Exception) {
                        reportEditSyncError(edit.name)
                      }
                    }
                  }
                }
            if (operation in confirmationHandlers) confirmationHandlers[operation] = handler
            else handler.dispose()
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

  override fun onCleared() {
    confirmationHandlers.values.forEach { it?.dispose() }
    confirmationHandlers.clear()
  }

  private fun reportEditSyncError(name: String) {
    mutableUiState.update { it.copy(editSyncError = FridgeEditSyncError(name)) }
  }

  fun dismissEditSyncError(error: FridgeEditSyncError) {
    mutableUiState.update { if (it.editSyncError === error) it.copy(editSyncError = null) else it }
  }

  private fun String?.normalizedFilter(): String? = this?.trim()?.takeIf(String::isNotEmpty)
}
