// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.staging

import android.content.Context
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
  suspend fun load(ownerId: String, householdId: String): InventorySubmission?

  suspend fun save(submission: InventorySubmission)
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
          val json = JSONObject(value)
          val rows = json.getJSONArray(Fields.ROWS)
          InventorySubmission(
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
                  json.optBoolean(Fields.DRAFT, false))
              .also { require(it.ownerId == ownerId && it.householdId == householdId) }
        }
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
        check(
            preferences
                .edit()
                .putString(key(submission.ownerId, submission.householdId), json.toString())
                .commit()) {
              "Could not retain the reviewed receipt"
            }
      }

  private fun key(ownerId: String, householdId: String) =
      JSONArray(listOf(ownerId, householdId)).toString()

  private fun JSONObject.requiredString(field: String): String =
      get(field) as? String ?: error("Invalid submission field")

  private companion object {
    const val FILE = "inventory-submissions"
  }
}
