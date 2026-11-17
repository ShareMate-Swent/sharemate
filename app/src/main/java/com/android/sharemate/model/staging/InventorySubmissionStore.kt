// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.staging

import android.content.Context
import com.android.sharemate.model.finance.FinanceFields
import com.android.sharemate.model.finance.SharedExpense
import com.android.sharemate.model.item.ExpenseProgress
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.item.InventorySubmissionFields as Fields
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Private durable receipt handoff. Persist before enqueueing; retain prices after inventory save.
 */
interface InventorySubmissionStore {
  /**
   * Loads the last submission for [ownerId] and [householdId], including an unconfirmed draft.
   * Returns null if the scope has no entry. Throws if the entry cannot be read or validated.
   */
  suspend fun load(ownerId: String, householdId: String): InventorySubmission?

  /**
   * Replaces the entry for [submission]'s owner and household. Returns after the journal write is
   * durable and throws if persistence fails.
   */
  suspend fun save(submission: InventorySubmission)

  /** Atomically retains a rejected operation separately and releases the active slot. */
  suspend fun setAside(submission: InventorySubmission) {
    error("Recovery archive unavailable")
  }

  /** Retained operations scoped to their original owner and household. */
  suspend fun deferred(ownerId: String, householdId: String): List<InventorySubmission> =
      emptyList()
}

/** Last submission per user/household, using checked disk commits on [dispatcher], never UI I/O. */
class AndroidInventorySubmissionStore(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : InventorySubmissionStore {
  private val applicationContext = context.applicationContext
  private val preferences
    get() = applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  override suspend fun load(ownerId: String, householdId: String): InventorySubmission? =
      withContext(dispatcher) {
        preferences.getString(key(ownerId, householdId), null)?.let { value ->
          decode(value, ownerId, householdId)
        }
      }

  private fun decode(value: String, ownerId: String, householdId: String): InventorySubmission {
    val json = JSONObject(value)
    val draft = json.get(Fields.DRAFT) as? Boolean ?: error("Invalid submission draft flag")
    val rows = json.getJSONArray(Fields.ROWS)
    return InventorySubmission(
            json.requiredString(Fields.ID),
            json.requiredString(Fields.OWNER),
            json.requiredString(Fields.HOUSEHOLD),
            List(rows.length()) { index ->
              val row = rows.getJSONObject(index)
              val quantity = row.get(Fields.QUANTITY)
              val price = row.get(Fields.PRICE)
              require(
                  (quantity is Int || quantity is Long) &&
                      (quantity as Number).toLong() in 1..Int.MAX_VALUE.toLong())
              require(price == JSONObject.NULL || price is Int || price is Long)
              StagedItem(
                  row.requiredString(Fields.ID),
                  row.requiredString(Fields.NAME),
                  (quantity as Number).toInt(),
                  (price as? Number)?.toLong())
            },
            draft,
            if (json.has(Fields.EXPENSE))
                json.getJSONObject(Fields.EXPENSE).let { expense ->
                  val shares = expense.getJSONObject(FinanceFields.SHARES)
                  SharedExpense(
                      json.requiredString(Fields.ID),
                      json.requiredString(Fields.HOUSEHOLD),
                      expense.requiredString(FinanceFields.PAID_BY),
                      expense.requiredLong(FinanceFields.TOTAL_AMOUNT_CENTS),
                      shares.keys().asSequence().associateWith { shares.requiredLong(it) })
                }
            else null,
            if (json.has(Fields.EXPENSE)) json.get(Fields.INVENTORY_CONFIRMED) as Boolean
            else false,
            if (json.has(Fields.EXPENSE))
                ExpenseProgress.valueOf(json.requiredString(Fields.EXPENSE_PROGRESS))
            else ExpenseProgress.QUEUED)
        .also { require(it.ownerId == ownerId && it.householdId == householdId) }
  }

  override suspend fun save(submission: InventorySubmission) =
      withContext(dispatcher) {
        val json =
            JSONObject()
                .put(Fields.ID, submission.id)
                .put(Fields.OWNER, submission.ownerId)
                .put(Fields.HOUSEHOLD, submission.householdId)
                .put(Fields.DRAFT, submission.draft)
                .put(
                    Fields.ROWS,
                    JSONArray(
                        submission.items.map { row ->
                          JSONObject()
                              .put(Fields.ID, row.id)
                              .put(Fields.NAME, row.name)
                              .put(Fields.QUANTITY, row.quantity)
                              .put(Fields.PRICE, row.priceCents ?: JSONObject.NULL)
                        }))
        submission.expense?.let { expense ->
          json
              .put(
                  Fields.EXPENSE,
                  JSONObject()
                      .put(FinanceFields.PAID_BY, expense.paidBy)
                      .put(FinanceFields.TOTAL_AMOUNT_CENTS, expense.totalAmountCents)
                      .put(FinanceFields.SHARES, JSONObject(expense.shares)))
              .put(Fields.INVENTORY_CONFIRMED, submission.inventoryConfirmed)
              .put(Fields.EXPENSE_PROGRESS, submission.expenseProgress.name)
        }
        check(
            preferences
                .edit()
                .putString(key(submission.ownerId, submission.householdId), json.toString())
                .remove(archiveKey(submission))
                .commit()) {
              "Could not retain the reviewed receipt"
            }
      }

  override suspend fun setAside(submission: InventorySubmission) =
      withContext(dispatcher) {
        require(
            submission.inventoryConfirmed && submission.expenseProgress == ExpenseProgress.REJECTED)
        val activeKey = key(submission.ownerId, submission.householdId)
        val value = checkNotNull(preferences.getString(activeKey, null))
        check(decode(value, submission.ownerId, submission.householdId) == submission)
        check(
            preferences.edit().putString(archiveKey(submission), value).remove(activeKey).commit())
      }

  override suspend fun deferred(ownerId: String, householdId: String): List<InventorySubmission> =
      withContext(dispatcher) {
        val prefix = "deferred:${key(ownerId, householdId)}:"
        preferences.all
            .filterKeys { it.startsWith(prefix) }
            .values
            .map { decode(it as String, ownerId, householdId) }
      }

  private fun archiveKey(submission: InventorySubmission) =
      "deferred:${key(submission.ownerId, submission.householdId)}:${submission.id}"

  private fun key(ownerId: String, householdId: String) =
      JSONArray(listOf(ownerId, householdId)).toString()

  private fun JSONObject.requiredString(field: String): String =
      get(field) as? String ?: error("Invalid submission field")

  private fun JSONObject.requiredLong(field: String): Long {
    val value = get(field)
    require(value is Int || value is Long)
    return (value as Number).toLong()
  }

  private companion object {
    const val FILE = "inventory-submissions"
  }
}
