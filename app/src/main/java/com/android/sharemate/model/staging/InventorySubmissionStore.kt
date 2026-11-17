// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.staging

import android.content.Context
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.item.InventorySubmissionFields as Fields
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Private durable receipt handoff. Persist before enqueueing; retain prices after inventory save.
 */
interface InventorySubmissionStore {
  /** Removes a draft whose photo metadata could not be committed. */
  suspend fun deleteDraft(ownerId: String, householdId: String, id: String) {}
  /** Local photo and timestamps associated with a stable receipt identity. */
  suspend fun details(ownerId: String, householdId: String, id: String): ReceiptDetails? = null

  /** Retains local presentation data after the receipt's submission has been saved. */
  suspend fun saveDetails(details: ReceiptDetails) {}
  /** Lists retained receipts for one account and household, newest first. */
  suspend fun list(ownerId: String, householdId: String): List<InventorySubmission> =
      listOfNotNull(load(ownerId, householdId))

  /** Loads a specific receipt without changing the active receipt. */
  suspend fun loadById(ownerId: String, householdId: String, id: String): InventorySubmission? =
      list(ownerId, householdId).firstOrNull { it.id == id }
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
}

data class ReceiptDetails(
    val id: String,
    val ownerId: String,
    val householdId: String,
    val photoUri: String,
    val scannedAtMillis: Long,
    val savedAtMillis: Long? = null,
)

/** Private submissions per user/household, using checked disk commits on [dispatcher]. */
class AndroidInventorySubmissionStore(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : InventorySubmissionStore {
  private val applicationContext = context.applicationContext
  private val mutex = Mutex()
  private val preferences
    get() = applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  override suspend fun load(ownerId: String, householdId: String): InventorySubmission? =
      withContext(dispatcher) {
        val scope = key(ownerId, householdId)
        val ids = preferences.getString(indexKey(scope), null)?.let(::JSONArray)
        if (ids != null) {
          if (ids.length() == 0) return@withContext null
          val value =
              preferences.getString(receiptKey(scope, ids.getString(0)), null)
                  ?: error("Missing retained receipt")
          return@withContext decode(value, ownerId, householdId)
        }
        preferences.getString(scope, null)?.let { decode(it, ownerId, householdId) }
      }

  override suspend fun list(ownerId: String, householdId: String): List<InventorySubmission> =
      withContext(dispatcher) {
        mutex.withLock {
          val scope = key(ownerId, householdId)
          val ids = preferences.getString(indexKey(scope), null)?.let(::JSONArray)
          if (ids == null) {
            listOfNotNull(load(ownerId, householdId))
          } else {
            List(ids.length()) { index ->
              val id = ids.getString(index)
              val value =
                  preferences.getString(receiptKey(scope, id), null)
                      ?: error("Missing retained receipt")
              decode(value, ownerId, householdId).also { require(it.id == id) }
            }
          }
        }
      }

  override suspend fun loadById(
      ownerId: String,
      householdId: String,
      id: String
  ): InventorySubmission? =
      withContext(dispatcher) {
        val scope = key(ownerId, householdId)
        preferences.getString(receiptKey(scope, id), null)?.let {
          decode(it, ownerId, householdId).also { submission -> require(submission.id == id) }
        } ?: load(ownerId, householdId)?.takeIf { it.id == id }
      }

  override suspend fun deleteDraft(ownerId: String, householdId: String, id: String) =
      withContext(dispatcher) {
        mutex.withLock {
          val scope = key(ownerId, householdId)
          val submission = loadById(ownerId, householdId, id) ?: return@withLock
          require(submission.draft)
          val encoded = preferences.getString(indexKey(scope), null) ?: return@withLock
          val index = JSONArray(encoded)
          val remaining = (0 until index.length()).map(index::getString).filterNot { it == id }
          check(
              preferences
                  .edit()
                  .remove(receiptKey(scope, id))
                  .remove(detailsKey(scope, id))
                  .putString(indexKey(scope), JSONArray(remaining).toString())
                  .commit())
        }
      }

  override suspend fun details(ownerId: String, householdId: String, id: String): ReceiptDetails? =
      withContext(dispatcher) {
        preferences.getString(detailsKey(key(ownerId, householdId), id), null)?.let { encoded ->
          val json = JSONObject(encoded)
          ReceiptDetails(
                  json.requiredString(Fields.ID),
                  json.requiredString(Fields.OWNER),
                  json.requiredString(Fields.HOUSEHOLD),
                  json.getString(PHOTO_URI),
                  json.getLong(SCANNED_AT),
                  json.optLong(SAVED_AT).takeIf { json.has(SAVED_AT) })
              .also {
                require(it.id == id && it.ownerId == ownerId && it.householdId == householdId)
              }
        }
      }

  override suspend fun saveDetails(details: ReceiptDetails) =
      withContext(dispatcher) {
        require(loadById(details.ownerId, details.householdId, details.id) != null)
        val json =
            JSONObject()
                .put(Fields.ID, details.id)
                .put(Fields.OWNER, details.ownerId)
                .put(Fields.HOUSEHOLD, details.householdId)
                .put(PHOTO_URI, details.photoUri)
                .put(SCANNED_AT, details.scannedAtMillis)
        details.savedAtMillis?.let { json.put(SAVED_AT, it) }
        check(
            preferences
                .edit()
                .putString(
                    detailsKey(key(details.ownerId, details.householdId), details.id),
                    json.toString())
                .commit())
      }

  override suspend fun save(submission: InventorySubmission) =
      withContext(dispatcher) {
        mutex.withLock {
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
          val scope = key(submission.ownerId, submission.householdId)
          val existing = preferences.getString(indexKey(scope), null)?.let(::JSONArray)
          val ids = mutableListOf<String>()
          if (existing != null) {
            repeat(existing.length()) { ids.add(existing.getString(it)) }
          } else {
            preferences.getString(scope, null)?.let { legacy ->
              ids.add(decode(legacy, submission.ownerId, submission.householdId).id)
            }
          }
          if (submission.id !in ids) ids.add(0, submission.id)
          val editor =
              preferences
                  .edit()
                  .putString(receiptKey(scope, submission.id), json.toString())
                  .putString(indexKey(scope), JSONArray(ids).toString())
          preferences.getString(scope, null)?.let { legacy ->
            val old = decode(legacy, submission.ownerId, submission.householdId)
            if (old.id != submission.id) editor.putString(receiptKey(scope, old.id), legacy)
          }
          check(editor.commit()) { "Could not retain the reviewed receipt" }
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
            draft)
        .also { require(it.ownerId == ownerId && it.householdId == householdId) }
  }

  private fun indexKey(scope: String) = "index:$scope"

  private fun receiptKey(scope: String, id: String) = "receipt:$scope:$id"

  private fun detailsKey(scope: String, id: String) = "details:$scope:$id"

  private fun key(ownerId: String, householdId: String) =
      JSONArray(listOf(ownerId, householdId)).toString()

  private fun JSONObject.requiredString(field: String): String =
      get(field) as? String ?: error("Invalid submission field")

  private companion object {
    const val FILE = "inventory-submissions"
    const val PHOTO_URI = "photoUri"
    const val SCANNED_AT = "scannedAtMillis"
    const val SAVED_AT = "savedAtMillis"
  }
}
