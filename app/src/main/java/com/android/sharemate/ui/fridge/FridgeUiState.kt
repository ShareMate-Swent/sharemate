// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.fridge

import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemSharing
import com.android.sharemate.model.user.ownerDisplayName

data class FridgeUiState(
    /** Type of the fridge shown. Items can only be shared in a [FridgeType.SHARED] fridge. */
    val fridgeType: FridgeType,
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
    /** Sharing chosen for the item being created; items are private unless shared explicitly. */
    val itemSharing: ItemSharing = ItemSharing.PERSONAL,
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
    val originalItemSharing: ItemSharing? = null,
    val showSharingSelector: Boolean = false,
    val canChangeSharing: Boolean = false,
    val currentUserId: String? = null,
    val ownerNames: Map<String, String> = emptyMap(),
)

/** Sharing is independent of both household association and ownership. */
data class ItemOwnership(
    val sharing: ItemSharing,
    val name: String? = null,
    val isCurrentUser: Boolean = false,
)

internal fun FridgeUiState.ownershipFor(item: Item) =
    ItemOwnership(item.sharing, ownerNameFor(item.ownerId), item.ownerId == currentUserId)

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

internal fun FridgeUiState.ownerNameFor(userId: String) = ownerDisplayName(ownerNames[userId])
