// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import com.android.sharemate.model.staging.StagedItem
import java.util.Collections
import java.util.UUID

/** Stable identity and reviewed data retained separately from inventory for the US-26 handoff. */
class InventorySubmission(
    val id: String,
    val ownerId: String,
    val householdId: String,
    items: List<StagedItem>,
    /** Local journal only: reviewed input retained before submission-size validation. */
    val draft: Boolean = false,
) {
  val items: List<StagedItem> = Collections.unmodifiableList(ArrayList(items))

  init {
    require(UUID.fromString(id).toString() == id)
    require(ownerId.isNotBlank() && '/' !in ownerId)
    require(householdId.isNotBlank() && '/' !in householdId)
    require(this.items.isNotEmpty())
    require(this.items.map { it.id }.toSet().size == this.items.size)
    require(
        draft ||
            (this.items.size <= MAX_ITEMS &&
                this.items.all {
                  it.name.length <= MAX_NAME_LENGTH && it.id.length <= MAX_NAME_LENGTH
                }))
  }

  /** One deterministic inventory document per reviewed row; never merge equal product names. */
  fun itemId(index: Int): String = "${id}_$index"

  override fun equals(other: Any?): Boolean =
      other is InventorySubmission &&
          id == other.id &&
          ownerId == other.ownerId &&
          householdId == other.householdId &&
          items == other.items &&
          draft == other.draft

  override fun hashCode(): Int = listOf(id, ownerId, householdId, items, draft).hashCode()

  companion object {
    /** Product policy keeps one atomic batch and its retained receipt comfortably bounded. */
    const val MAX_ITEMS = 100
    const val MAX_NAME_LENGTH = 1000
  }
}

/** Cached absence is pending; only a server snapshot can confirm presence or absence. */
enum class InventorySubmissionStatus {
  PENDING,
  CONFIRMED,
  MISSING
}

/** Shared field names for the private submission marker and the durable local journal. */
object InventorySubmissionFields {
  const val COLLECTION = "inventorySubmissions"
  const val ID = "id"
  const val OWNER = "ownerId"
  const val HOUSEHOLD = "householdId"
  const val ROWS = "rows"
  const val NAME = "name"
  const val QUANTITY = "quantity"
  const val PRICE = "priceCents"
  const val DRAFT = "draft"
}
