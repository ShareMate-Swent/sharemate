// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.item

import com.android.sharemate.model.finance.SharedExpense
import com.android.sharemate.model.staging.StagedItem
import com.android.sharemate.model.text.TextLimits
import com.android.sharemate.model.text.exceeds
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
    val expense: SharedExpense? = null,
    val inventoryConfirmed: Boolean = false,
    val expenseProgress: ExpenseProgress = ExpenseProgress.QUEUED,
    /** Selected from the household type and retained for offline replay. */
    val sharing: ItemSharing = ItemSharing.SHARED,
) {
  val items: List<StagedItem> = Collections.unmodifiableList(ArrayList(items))

  /** True for a nonempty receipt whose rows were all excluded from cost sharing. */
  val allRowsExcluded: Boolean
    get() = items.isNotEmpty() && items.none { it.includedInExpense }

  init {
    require(expenseProgress == ExpenseProgress.QUEUED || inventoryConfirmed)
    expense?.let {
      require(it.totalAmountCents <= 100_000_000L)
      var remaining = it.totalAmountCents
      items.forEach { row ->
        if (!row.includedInExpense) return@forEach
        val cents = requireNotNull(row.priceCents)
        require(cents in 0..remaining)
        remaining -= cents
      }
      require(remaining == 0L)
    }
    require(expense == null || (!draft && expense.id == id && expense.householdId == householdId))
    require(expense != null || expenseProgress == ExpenseProgress.QUEUED)
    require(expense != null || !inventoryConfirmed || (!draft && allRowsExcluded))
    require(UUID.fromString(id).toString() == id)
    require(ownerId.isNotBlank() && '/' !in ownerId)
    require(householdId.isNotBlank() && '/' !in householdId)
    require(draft || this.items.isNotEmpty())
    require(this.items.map { it.id }.toSet().size == this.items.size)
    require(
        draft ||
            (this.items.size <= MAX_ITEMS &&
                this.items.all {
                  !it.name.exceeds(TextLimits.ITEM_NAME) &&
                      it.quantity <= ItemQuantity.MAX &&
                      it.id.length <= MAX_ID_LENGTH
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
          draft == other.draft &&
          expense == other.expense &&
          inventoryConfirmed == other.inventoryConfirmed &&
          expenseProgress == other.expenseProgress &&
          sharing == other.sharing

  override fun hashCode(): Int =
      listOf(
              id,
              ownerId,
              householdId,
              items,
              draft,
              expense,
              inventoryConfirmed,
              expenseProgress,
              sharing)
          .hashCode()

  fun withProgress(confirmed: Boolean, progress: ExpenseProgress = expenseProgress) =
      InventorySubmission(
          id, ownerId, householdId, items, draft, expense, confirmed, progress, sharing)

  companion object {
    /** Product policy keeps one atomic batch and its retained receipt comfortably bounded. */
    const val MAX_ITEMS = 100
    const val MAX_ID_LENGTH = 1000
  }
}

/** Local purchase progress; a transport failure never proves that no expense was committed. */
enum class ExpenseProgress {
  QUEUED,
  CONFIRMED,
  REJECTED,
  UNKNOWN
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
  const val INCLUDED_IN_EXPENSE = "includedInExpense"
  const val DRAFT = "draft"
  const val SHARING = "sharing"
  const val EXPENSE = "expense"
  const val INVENTORY_CONFIRMED = "inventoryConfirmed"
  const val EXPENSE_PROGRESS = "expenseProgress"
}
