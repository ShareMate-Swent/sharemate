// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.staging

import android.content.Context
import com.android.sharemate.model.finance.FinanceFields
import com.android.sharemate.model.finance.SharedExpense
import com.android.sharemate.model.item.ExpenseProgress
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.item.InventorySubmissionFields as Fields
import com.android.sharemate.model.receipt.ReceiptCacheLifecycle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Private durable receipt handoff. Persist before enqueueing; retain prices after inventory save.
 */
interface InventorySubmissionStore {
  /** Signals that this store's household lease ended, including departure on another device. */
  fun invalidations(householdId: String): kotlinx.coroutines.flow.Flow<Unit> =
      kotlinx.coroutines.flow.emptyFlow()
  /** Binds shared receipt reads and edits to this viewer without changing creator identities. */
  fun forViewer(viewerId: String): InventorySubmissionStore = this

  /** Re-enqueues retained work after an interrupted process, without replacing running jobs. */
  suspend fun resumeSync(ownerId: String, householdId: String) {}
  /**
   * Applies explicit server deletion markers without interpreting missing snapshots as deletion.
   */
  suspend fun applyCloudDeletions(ownerId: String, householdId: String, ids: Set<String>) {}
  /** Locally discarded identities must not be restored by a delayed cloud snapshot. */
  suspend fun isDeleted(ownerId: String, householdId: String, id: String): Boolean = false
  /** Cached household receipts visible to this viewer, excluding their own history. */
  suspend fun sharedReceipts(viewerId: String, householdId: String): List<InventorySubmission> =
      emptyList()
  /** Replaces the viewer-scoped shared cache after an authorized cloud observation. */
  suspend fun cacheShared(
      viewerId: String,
      householdId: String,
      entries: List<com.android.sharemate.model.receipt.ReceiptCloudEntry>
  ) {}
  /** Removes retained rows and metadata after remote deletion is acknowledged, or for a draft. */
  suspend fun deleteReceipt(ownerId: String, householdId: String, id: String) {
    error("Receipt deletion unavailable")
  }

  suspend fun pendingCorrection(
      ownerId: String,
      householdId: String,
      id: String
  ): ReceiptCorrection? = null

  suspend fun saveCorrection(correction: ReceiptCorrection) {
    error("Correction journal unavailable")
  }

  /** Commits an acknowledgement only while the same operation still owns the journal. */
  suspend fun acknowledgeCorrection(correction: ReceiptCorrection) {
    error("Atomic correction acknowledgement unavailable")
  }

  suspend fun clearCorrection(ownerId: String, householdId: String, id: String) {}
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

  /** Atomically retains a rejected operation separately and releases the active slot. */
  suspend fun setAside(submission: InventorySubmission) {
    error("Recovery archive unavailable")
  }

  /** Retained operations scoped to their original owner and household. */
  suspend fun deferred(ownerId: String, householdId: String): List<InventorySubmission> =
      emptyList()
}

data class ReceiptDetails(
    val id: String,
    val ownerId: String,
    val householdId: String,
    val photoUri: String,
    val scannedAtMillis: Long,
    val savedAtMillis: Long? = null,
    val title: String = "",
    val draftPaidBy: String? = null,
    val draftParticipants: Set<String> = emptySet(),
)

/** Private submissions per user/household, using checked disk commits on [dispatcher]. */
class AndroidInventorySubmissionStore(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val legacyActive: Boolean = false,
    private val scheduleSync: Boolean = false,
    private val viewerId: String? = null,
    private val lifecycle: ReceiptCacheLifecycle = ReceiptCacheLifecycle(context),
) : InventorySubmissionStore {
  private val applicationContext = context.applicationContext

  override fun invalidations(householdId: String): kotlinx.coroutines.flow.Flow<Unit> =
      kotlinx.coroutines.flow.callbackFlow {
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
              if (!lifecycle.isCurrent(householdId)) trySend(Unit)
            }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        if (!lifecycle.isCurrent(householdId)) trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
      }

  private val preferences
    get() = applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  private fun enqueue(
      receipt: InventorySubmission,
      delete: Boolean = false,
      replace: Boolean = true
  ) =
      lifecycle.write(receipt.householdId) {
        ReceiptSyncWorker.enqueue(applicationContext, receipt, delete, replace)
      }

  override fun forViewer(viewerId: String): InventorySubmissionStore =
      AndroidInventorySubmissionStore(
          applicationContext, dispatcher, legacyActive, scheduleSync, viewerId, lifecycle)

  override suspend fun resumeSync(ownerId: String, householdId: String) {
    if (!scheduleSync || legacyActive) return
    for (receipt in list(ownerId, householdId) + sharedReceipts(ownerId, householdId)) {
      if (receipt.ownerId == ownerId ||
          pendingCorrection(receipt.ownerId, householdId, receipt.id) != null)
          enqueue(receipt, replace = false)
    }
    withContext(dispatcher) {
      val prefix = "deleted:${key(ownerId, householdId)}:"
      for (entry in
          preferences.all.filterKeys { it.startsWith(prefix) }.filterValues { it == true }) {
        val receipt =
            InventorySubmission(
                entry.key.removePrefix(prefix), ownerId, householdId, emptyList(), draft = true)
        enqueue(receipt, delete = true, replace = false)
      }
    }
  }

  override suspend fun applyCloudDeletions(ownerId: String, householdId: String, ids: Set<String>) {
    val cache = AndroidInventorySubmissionStore(applicationContext, dispatcher)
    for (id in ids) {
      cache.deleteReceipt(ownerId, householdId, id)
      com.android.sharemate.model.receipt
          .AndroidReceiptPhotoStore(applicationContext, dispatcher)
          .delete(id)
    }
  }

  override suspend fun isDeleted(ownerId: String, householdId: String, id: String) =
      withContext(dispatcher) {
        preferences.getBoolean("deleted:${ownerKey(ownerId, householdId)}:$id", false)
      }

  override suspend fun sharedReceipts(
      viewerId: String,
      householdId: String
  ): List<InventorySubmission> =
      withContext(dispatcher) {
        val ids = JSONArray(preferences.getString("shared:${key(viewerId, householdId)}", "[]"))
        (0 until ids.length()).mapNotNull { index ->
          val entry = ids.getJSONObject(index)
          val owner = entry.getString("owner")
          val id = entry.getString("id")
          if (isDeleted(owner, householdId, id)) null
          else
              AndroidInventorySubmissionStore(
                      applicationContext, dispatcher, viewerId = viewerId, lifecycle = lifecycle)
                  .loadById(owner, householdId, id)
        }
      }

  override suspend fun cacheShared(
      viewerId: String,
      householdId: String,
      entries: List<com.android.sharemate.model.receipt.ReceiptCloudEntry>
  ) {
    check(lifecycle.isCurrent(householdId))
    val cache =
        AndroidInventorySubmissionStore(
            applicationContext, dispatcher, viewerId = viewerId, lifecycle = lifecycle)
    val shared =
        entries.filter {
          it.submission.ownerId != viewerId &&
              !it.submission.draft &&
              it.submission.householdId == householdId &&
              !isDeleted(it.submission.ownerId, householdId, it.submission.id)
        }
    withContext(dispatcher) {
      val ids =
          JSONArray(
              shared.map {
                JSONObject().put("owner", it.submission.ownerId).put("id", it.submission.id)
              })
      val scope = key(viewerId, householdId)
      val previous = JSONArray(preferences.getString("cached:$scope", "[]"))
      val cached =
          ((0 until previous.length()).map(previous::getJSONObject) +
                  (0 until ids.length()).map(ids::getJSONObject))
              .distinctBy { it.toString() }
      check(
          preferences
              .edit()
              .putString("cached:$scope", JSONArray(cached).toString())
              .putString("shared:$scope", ids.toString())
              .let { lifecycle.commit(householdId, it) })
    }
    for (entry in shared) {
      cache.saveLocally(entry.submission)
      cache.saveDetailsLocally(entry.details)
    }
  }

  override suspend fun load(ownerId: String, householdId: String): InventorySubmission? =
      withContext(dispatcher) {
        val scope = key(ownerId, householdId)
        if (legacyActive) {
          return@withContext preferences.getString(scope, null)?.let {
            decode(it, ownerId, householdId)
          }
        }
        val ids = preferences.getString(indexKey(scope), null)?.let(::JSONArray)
        if (ids != null) {
          if (ids.length() == 0) return@withContext null
          val value =
              preferences.getString(receiptKey(scope, ids.getString(0)), null)
                  ?: error("Missing retained receipt")
          return@withContext decode(value, ownerId, householdId)
        }
        null
      }

  override suspend fun list(ownerId: String, householdId: String): List<InventorySubmission> =
      withContext(dispatcher) {
        mutationMutex.withLock {
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
      deleteRetained(ownerId, householdId, id, true)

  override suspend fun deleteReceipt(ownerId: String, householdId: String, id: String) =
      deleteRetained(ownerId, householdId, id, false)

  private suspend fun deleteRetained(
      ownerId: String,
      householdId: String,
      id: String,
      draftOnly: Boolean
  ) =
      withContext(dispatcher) {
        mutationMutex.withLock {
          val scope = key(ownerId, householdId)
          val submission = loadById(ownerId, householdId, id)
          if (draftOnly && submission == null) return@withLock
          require(!draftOnly || submission!!.draft)
          val encoded = preferences.getString(indexKey(scope), "[]")
          val index = JSONArray(encoded)
          val remaining = (0 until index.length()).map(index::getString).filterNot { it == id }
          check(
              preferences
                  .edit()
                  .remove(receiptKey(scope, id))
                  .remove(detailsKey(scope, id))
                  .putBoolean("deleted:${ownerKey(ownerId, householdId)}:$id", true)
                  .putString(indexKey(scope), JSONArray(remaining).toString())
                  .let { lifecycle.commit(householdId, it) })
          if (scheduleSync) enqueue(submission, delete = true)
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
                  json.optLong(SAVED_AT).takeIf { json.has(SAVED_AT) },
                  json.optString("title", ""),
                  json.optString("draftPaidBy", "").takeIf { it.isNotBlank() },
                  json
                      .optJSONArray("draftParticipants")
                      ?.let { participants ->
                        (0 until participants.length()).map(participants::getString).toSet()
                      }
                      .orEmpty())
              .also {
                require(it.id == id && it.ownerId == ownerId && it.householdId == householdId)
              }
        }
      }

  override suspend fun saveDetails(details: ReceiptDetails) {
    saveDetailsLocally(details)
    if (scheduleSync && !legacyActive)
        loadById(details.ownerId, details.householdId, details.id)?.let { enqueue(it) }
  }

  private suspend fun saveDetailsLocally(details: ReceiptDetails) =
      withContext(dispatcher) {
        require(loadById(details.ownerId, details.householdId, details.id) != null)
        val json =
            JSONObject()
                .put(Fields.ID, details.id)
                .put(Fields.OWNER, details.ownerId)
                .put(Fields.HOUSEHOLD, details.householdId)
                .put(PHOTO_URI, details.photoUri)
                .put(SCANNED_AT, details.scannedAtMillis)
                .put("title", details.title)
                .put("draftParticipants", JSONArray(details.draftParticipants.toList()))
        details.draftPaidBy?.let { json.put("draftPaidBy", it) }
        details.savedAtMillis?.let { json.put(SAVED_AT, it) }
        check(
            preferences
                .edit()
                .putString(
                    detailsKey(key(details.ownerId, details.householdId), details.id),
                    json.toString())
                .let { lifecycle.commit(details.householdId, it) })
      }

  override suspend fun save(submission: InventorySubmission) {
    saveLocally(submission)
    if (scheduleSync && !legacyActive) {
      enqueue(submission)
    }
  }

  private suspend fun saveLocally(submission: InventorySubmission) =
      withContext(dispatcher) {
        mutationMutex.withLock {
          val json = encode(submission)
          val scope = key(submission.ownerId, submission.householdId)
          check(
              !preferences.getBoolean(
                  "deleted:${ownerKey(submission.ownerId, submission.householdId)}:${submission.id}",
                  false)) {
                "Receipt was deleted"
              }
          // A delayed editor must never overwrite a purchase already queued by another coordinator.
          if (!legacyActive && submission.draft) {
            preferences.getString(receiptKey(scope, submission.id), null)?.let { previous ->
              check(decode(previous, submission.ownerId, submission.householdId).draft) {
                "This receipt has already been submitted"
              }
            }
          }
          if (legacyActive) {
            check(
                preferences
                    .edit()
                    .putString(scope, json.toString())
                    .remove(archiveKey(submission))
                    .let { lifecycle.commit(submission.householdId, it) }) {
                  "Could not retain the reviewed receipt"
                }
            return@withLock
          }
          val existing = preferences.getString(indexKey(scope), null)?.let(::JSONArray)
          val ids = mutableListOf<String>()
          if (existing != null) repeat(existing.length()) { ids.add(existing.getString(it)) }
          if (submission.id !in ids) ids.add(0, submission.id)
          val editor =
              preferences
                  .edit()
                  .putString(receiptKey(scope, submission.id), json.toString())
                  .putString(indexKey(scope), JSONArray(ids).toString())
                  .remove(archiveKey(submission))
          check(editor.let { lifecycle.commit(submission.householdId, it) }) {
            "Could not retain the reviewed receipt"
          }
        }
      }

  internal fun encode(submission: InventorySubmission): JSONObject {
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
    return json
  }

  override suspend fun pendingCorrection(
      ownerId: String,
      householdId: String,
      id: String
  ): ReceiptCorrection? =
      withContext(dispatcher) {
        preferences.getString("correction:${key(ownerId, householdId)}:$id", null)?.let {
          val json = JSONObject(it)
          ReceiptCorrection(
                  json.getString("operationId"),
                  decode(json.getJSONObject("original").toString(), ownerId, householdId),
                  decode(json.getJSONObject("replacement").toString(), ownerId, householdId),
                  json.getString("title"),
                  json.optString("originalTitle", ""))
              .also { correction -> require(correction.original.id == id) }
        }
      }

  override suspend fun saveCorrection(correction: ReceiptCorrection) =
      withContext(dispatcher) {
        mutationMutex.withLock {
          val original = correction.original
          val json =
              JSONObject()
                  .put("operationId", correction.operationId)
                  .put("original", encode(original))
                  .put("replacement", encode(correction.replacement))
                  .put("title", correction.title)
                  .put("originalTitle", correction.originalTitle)
          check(
              preferences
                  .edit()
                  .putString(
                      "correction:${key(original.ownerId, original.householdId)}:${original.id}",
                      json.toString())
                  .let { lifecycle.commit(original.householdId, it) })
        }
        if (scheduleSync) enqueue(correction.original)
      }

  override suspend fun clearCorrection(ownerId: String, householdId: String, id: String) =
      withContext(dispatcher) {
        mutationMutex.withLock {
          check(
              preferences.edit().remove("correction:${key(ownerId, householdId)}:$id").let {
                lifecycle.commit(householdId, it)
              })
        }
      }

  override suspend fun acknowledgeCorrection(correction: ReceiptCorrection) =
      withContext(dispatcher) {
        mutationMutex.withLock {
          val original = correction.original
          val scope = key(original.ownerId, original.householdId)
          if (pendingCorrection(original.ownerId, original.householdId, original.id)?.operationId !=
              correction.operationId ||
              isDeleted(original.ownerId, original.householdId, original.id))
              return@withLock
          val editor =
              preferences
                  .edit()
                  .putString(
                      receiptKey(scope, original.id),
                      encode(correction.replacement.withProgress(true, ExpenseProgress.CONFIRMED))
                          .toString())
                  .remove("correction:$scope:${original.id}")
          preferences.getString(detailsKey(scope, original.id), null)?.let {
            editor.putString(
                detailsKey(scope, original.id),
                JSONObject(it).put("title", correction.title).toString())
          }
          check(lifecycle.commit(original.householdId, editor))
        }
        if (scheduleSync) enqueue(correction.replacement)
      }

  internal fun decode(value: String, ownerId: String, householdId: String): InventorySubmission {
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

  private fun indexKey(scope: String) = "index:$scope"

  private fun receiptKey(scope: String, id: String) = "receipt:$scope:$id"

  private fun detailsKey(scope: String, id: String) = "details:$scope:$id"

  override suspend fun setAside(submission: InventorySubmission) =
      withContext(dispatcher) {
        mutationMutex.withLock {
          require(
              submission.inventoryConfirmed &&
                  submission.expenseProgress == ExpenseProgress.REJECTED)
          val scope = key(submission.ownerId, submission.householdId)
          if (legacyActive) {
            val value = checkNotNull(preferences.getString(scope, null))
            check(decode(value, submission.ownerId, submission.householdId) == submission)
            check(
                preferences.edit().putString(archiveKey(submission), value).remove(scope).let {
                  lifecycle.commit(submission.householdId, it)
                })
            return@withLock
          }
          val value = checkNotNull(preferences.getString(receiptKey(scope, submission.id), null))
          check(decode(value, submission.ownerId, submission.householdId) == submission)
          val index = JSONArray(checkNotNull(preferences.getString(indexKey(scope), null)))
          val remaining =
              (0 until index.length()).map(index::getString).filterNot { it == submission.id }
          check(
              preferences
                  .edit()
                  .putString(archiveKey(submission), value)
                  .remove(receiptKey(scope, submission.id))
                  .putString(indexKey(scope), JSONArray(remaining).toString())
                  .let { lifecycle.commit(submission.householdId, it) })
        }
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

  private fun ownerKey(ownerId: String, householdId: String) =
      JSONArray(listOf(ownerId, householdId)).toString()

  private fun key(ownerId: String, householdId: String) =
      if (viewerId == null || viewerId == ownerId) ownerKey(ownerId, householdId)
      else JSONArray(listOf(viewerId, householdId, ownerId)).toString()

  private fun JSONObject.requiredString(field: String): String =
      get(field) as? String ?: error("Invalid submission field")

  private fun JSONObject.requiredLong(field: String): Long {
    val value = get(field)
    require(value is Int || value is Long)
    return (value as Number).toLong()
  }

  private companion object {
    const val FILE = "inventory-submissions"
    const val PHOTO_URI = "photoUri"
    const val SCANNED_AT = "scannedAtMillis"
    const val SAVED_AT = "savedAtMillis"

    // All instances write the same preferences file within this application process.
    val mutationMutex = Mutex()
  }
}
