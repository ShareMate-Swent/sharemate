// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.staging

import androidx.lifecycle.ViewModel
import com.android.sharemate.model.staging.PriceInput
import com.android.sharemate.model.staging.Prices
import com.android.sharemate.model.staging.StagedItem
import com.android.sharemate.model.text.TextLimits
import com.android.sharemate.model.text.limitedTo
import java.util.UUID
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

/** Values typed in the item dialog. A null [itemId] identifies a new row. */
data class StagedItemDraft(
    val itemId: String?,
    val name: String,
    val quantity: String,
    val price: String,
    val error: StagedItemError? = null,
)

/** State of the staging screen: the items and any open draft. */
data class StagingUiState(
    val items: List<StagedItem> = emptyList(),
    val draft: StagedItemDraft? = null,
) {
  /** The list can be confirmed when it has items and no item dialog is open. */
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

  /** Opens a blank draft with the default quantity, leaving the current list unchanged. */
  fun startAdding() {
    mutableState.update {
      it.copy(
          draft =
              StagedItemDraft(
                  itemId = null, name = "", quantity = DEFAULT_ADD_QUANTITY, price = ""))
    }
  }

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

  /** Updates the name, cut to [TextLimits.ITEM_NAME] characters so a paste cannot exceed it. */
  fun updateDraftName(name: String) = updateDraft {
    it.copy(name = name.limitedTo(TextLimits.ITEM_NAME), error = null)
  }

  fun updateDraftQuantity(quantity: String) = updateDraft {
    it.copy(quantity = quantity, error = null)
  }

  fun updateDraftPrice(price: String) = updateDraft { it.copy(price = price, error = null) }

  private fun updateDraft(update: (StagedItemDraft) -> StagedItemDraft) {
    mutableState.update { state -> state.draft?.let { state.copy(draft = update(it)) } ?: state }
  }

  /** Adds or edits a row when the draft is valid; otherwise keeps it open with an error. */
  fun saveDraft() {
    mutableState.update { state ->
      val draft = state.draft ?: return@update state
      val name = draft.name.trim()
      val quantity = draft.quantity.trim().toIntOrNull()?.takeIf { it > 0 }
      val price = Prices.parse(draft.price)
      val error =
          when {
            name.isEmpty() -> StagedItemError.NAME_REQUIRED
            quantity == null -> StagedItemError.INVALID_QUANTITY
            price == PriceInput.Invalid -> StagedItemError.INVALID_PRICE
            else -> null
          }
      if (error != null) return@update state.copy(draft = draft.copy(error = error))
      val updated =
          StagedItem(
              id = draft.itemId ?: UUID.randomUUID().toString(),
              name = name,
              quantity = checkNotNull(quantity),
              priceCents = (price as? PriceInput.Valid)?.cents)
      val items =
          if (draft.itemId == null) state.items + updated
          else state.items.map { if (it.id == updated.id) updated else it }
      state.copy(items = items, draft = null)
    }
  }

  /** Closes the item dialog without changing the list. */
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

private const val DEFAULT_ADD_QUANTITY = "1"
