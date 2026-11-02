// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.staging

import androidx.lifecycle.ViewModel
import com.android.sharemate.model.staging.PriceInput
import com.android.sharemate.model.staging.Prices
import com.android.sharemate.model.staging.StagedItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Why the edited values were not applied. */
enum class StagedItemError {
  NAME_REQUIRED,
  INVALID_QUANTITY,
  INVALID_PRICE,
}

/** Values typed in the edit dialog, kept as text until they are validated. */
data class StagedItemDraft(
    val itemId: String,
    val name: String,
    val quantity: String,
    val price: String,
    val error: StagedItemError? = null,
)

/** State of the staging screen: the items and, while editing, the open draft. */
data class StagingUiState(
    val items: List<StagedItem> = emptyList(),
    val draft: StagedItemDraft? = null,
) {
  /** The list can be confirmed when it has items and no edit is in progress. */
  val canConfirm: Boolean
    get() = items.isNotEmpty() && draft == null
}

/**
 * Lets the user review scanned items before they are saved. It neither knows where the items come
 * from nor saves them: the screen receives [initialItems] and hands [confirmedItems] back.
 */
class StagingViewModel(initialItems: List<StagedItem>) : ViewModel() {
  init {
    require(initialItems.map { it.id }.toSet().size == initialItems.size) {
      "Staged item ids must be unique"
    }
  }

  private val mutableState = MutableStateFlow(StagingUiState(items = initialItems))
  val state: StateFlow<StagingUiState> = mutableState.asStateFlow()

  /** Opens the edit dialog for [itemId] with its current values. */
  fun startEditing(itemId: String) {
    val item = state.value.items.find { it.id == itemId } ?: return
    mutableState.update {
      it.copy(
          draft =
              StagedItemDraft(
                  itemId = item.id,
                  name = item.name,
                  quantity = item.quantity.toString(),
                  price = Prices.format(item.priceCents)))
    }
  }

  fun updateDraftName(name: String) = updateDraft { it.copy(name = name, error = null) }

  fun updateDraftQuantity(quantity: String) = updateDraft {
    it.copy(quantity = quantity, error = null)
  }

  fun updateDraftPrice(price: String) = updateDraft { it.copy(price = price, error = null) }

  private fun updateDraft(update: (StagedItemDraft) -> StagedItemDraft) {
    mutableState.update { state -> state.draft?.let { state.copy(draft = update(it)) } ?: state }
  }

  /** Applies the draft when every value is valid; otherwise keeps it open with an error. */
  fun saveDraft() {
    val draft = state.value.draft ?: return
    val name = draft.name.trim()
    val quantity = draft.quantity.trim().toIntOrNull()?.takeIf { it > 0 }
    val price = Prices.parse(draft.price)
    if (name.isEmpty()) return updateDraftError(StagedItemError.NAME_REQUIRED)
    if (quantity == null) return updateDraftError(StagedItemError.INVALID_QUANTITY)
    if (price == PriceInput.Invalid) return updateDraftError(StagedItemError.INVALID_PRICE)
    val priceCents = (price as? PriceInput.Valid)?.cents
    mutableState.update { state ->
      state.copy(
          items =
              state.items.map { item ->
                if (item.id == draft.itemId) {
                  item.copy(name = name, quantity = quantity, priceCents = priceCents)
                } else {
                  item
                }
              },
          draft = null)
    }
  }

  private fun updateDraftError(error: StagedItemError) = updateDraft { it.copy(error = error) }

  /** Closes the edit dialog without changing the item. */
  fun cancelEditing() {
    mutableState.update { it.copy(draft = null) }
  }

  /** Removes [itemId] from the list, closing its edit dialog if it was open. */
  fun deleteItem(itemId: String) {
    mutableState.update { state ->
      state.copy(
          items = state.items.filterNot { it.id == itemId },
          draft = state.draft?.takeIf { it.itemId != itemId })
    }
  }

  /** The final list to save, or null when it cannot be confirmed yet. */
  fun confirmedItems(): List<StagedItem>? = state.value.takeIf { it.canConfirm }?.items
}
