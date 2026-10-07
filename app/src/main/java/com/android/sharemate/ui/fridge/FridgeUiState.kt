// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import com.android.sharemate.model.item.Item

data class FridgeUiState(
    val items: List<Item> = emptyList(),
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val isAddItemDialogOpen: Boolean = false,
    val itemName: String = "",
    val itemCategory: String = "",
    val expirationDateInput: String = "",
    val isSaving: Boolean = false,
    val formError: FridgeFormError? = null,
    val pendingRemovalItem: Item? = null,
    val isDeleting: Boolean = false,
    val removalFailed: Boolean = false
)

enum class FridgeFormError {
  NAME_REQUIRED,
  INVALID_EXPIRATION_DATE,
  SAVE_FAILED
}
