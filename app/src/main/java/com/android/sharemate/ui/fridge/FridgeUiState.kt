// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.fridge

import com.android.sharemate.model.item.Item

data class FridgeUiState(
    val items: List<Item> = emptyList(),
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val isAddItemDialogOpen: Boolean = false,
    val editingItemId: String? = null,
    val itemQuantity: String = "1",
    val editSyncError: FridgeEditSyncError? = null,
    val pendingStatusIds: Set<String> = emptySet(),
    val statusError: FridgeStatusError? = null,
    val itemName: String = "",
    val itemCategory: String = "",
    val expirationDateInput: String = "",
    val isScanningBarcode: Boolean = false,
    val barcodeValue: String? = null,
    val barcodeScanFailed: Boolean = false,
    val barcodeLookupError: BarcodeLookupError? = null,
    val isSaving: Boolean = false,
    val formError: FridgeFormError? = null,
    val pendingRemovalItem: Item? = null,
    val isDeleting: Boolean = false,
    val pendingDeletionIds: Set<String> = emptySet(),
    val removalFailed: Boolean = false,
    val removalSyncError: FridgeRemovalSyncError? = null,
)

enum class BarcodeLookupError {
  UNREADABLE,
  SCANNER_FAILED,
  NOT_FOUND,
  LOOKUP_FAILED,
  NO_DETAILS,
}

/** Keeps photo confirmation retryable while the form exposes the specific lookup error. */
class BarcodeLookupException(val error: BarcodeLookupError) : Exception()

enum class FridgeFormError {
  NAME_REQUIRED,
  INVALID_QUANTITY,
  INVALID_EXPIRATION_DATE,
  SAVE_FAILED,
}

/** Each rejection is a distinct event, even when the item name is unchanged. */
class FridgeEditSyncError(val itemName: String)

class FridgeStatusError(val itemName: String, val isSyncFailure: Boolean)

class FridgeRemovalSyncError(val itemName: String)
