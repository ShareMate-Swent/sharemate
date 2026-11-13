// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.item

import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.text.TextLimits
import com.android.sharemate.model.text.exceeds
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Filter
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await

class FirebaseItemRepository(private val firestore: FirebaseFirestore) : ItemRepository {

  private val items
    get() = firestore.collection(FirestoreCollections.ITEMS)

  override fun getFridgeItems(householdId: String, userId: String): Flow<List<Item>> =
      if (householdId.isBlank() || userId.isBlank()) {
        flowOf(emptyList())
      } else {
        // The security rules only accept a query whose every result the caller may read, so it
        // must name both the household and either the shared items or the caller's own items.
        observe(
            items.where(
                Filter.and(
                    Filter.equalTo(ItemFields.HOUSEHOLD_ID, householdId),
                    Filter.or(
                        Filter.equalTo(ItemFields.SHARING, ItemSharing.SHARED.name),
                        Filter.equalTo(ItemFields.OWNER_ID, userId)))))
      }

  override suspend fun addItem(item: Item): String? {
    if (item.name.isBlank() || item.ownerId.isBlank() || item.quantity <= 0) return null
    if (!item.hasValidTexts()) return null
    return try {
      val document = items.document()
      val savedItem = item.copy(id = document.id, name = item.name.trim())
      document.set(savedItem).await()
      document.id
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      null
    }
  }

  override fun addItems(submission: InventorySubmission): ItemWrite =
      submit(submission.id) {
        require(!submission.draft) { "A retained draft must be validated before submission" }
        val batch = firestore.batch()
        submission.items.forEachIndexed { index, row ->
          val item =
              Item(
                  id = submission.itemId(index),
                  name = row.name,
                  quantity = row.quantity,
                  ownerId = submission.ownerId,
                  householdId = submission.householdId,
                  sharing = ItemSharing.SHARED,
                  status = ItemStatus.ACTIVE)
          batch.set(items.document(item.id), item)
        }
        batch.set(submissionDocument(submission.ownerId, submission.id), submission.markerData())
        batch.commit()
      }

  private fun InventorySubmission.markerData() =
      mapOf(
          InventorySubmissionFields.ID to id,
          InventorySubmissionFields.OWNER to ownerId,
          InventorySubmissionFields.HOUSEHOLD to householdId,
          InventorySubmissionFields.ROWS to
              items.map { row ->
                mapOf(
                    InventorySubmissionFields.ID to row.id,
                    InventorySubmissionFields.NAME to row.name,
                    InventorySubmissionFields.QUANTITY to row.quantity.toLong(),
                    InventorySubmissionFields.PRICE to row.priceCents)
              })

  override fun observeSubmission(submission: InventorySubmission): Flow<InventorySubmissionStatus> =
      callbackFlow {
            val listener =
                submissionDocument(submission.ownerId, submission.id).addSnapshotListener(
                    MetadataChanges.INCLUDE) { snapshot, error ->
                      if (error != null) {
                        close(error)
                        return@addSnapshotListener
                      }
                      if (snapshot == null) return@addSnapshotListener
                      trySend(
                          when {
                            snapshot.metadata.isFromCache || snapshot.metadata.hasPendingWrites() ->
                                InventorySubmissionStatus.PENDING
                            snapshot.exists() && snapshot.data == submission.markerData() ->
                                InventorySubmissionStatus.CONFIRMED
                            snapshot.exists() -> {
                              close(IllegalStateException("Submission data does not match"))
                              return@addSnapshotListener
                            }
                            else -> InventorySubmissionStatus.MISSING
                          })
                    }
            awaitClose { listener.remove() }
          }
          .distinctUntilChanged()

  private fun submissionDocument(ownerId: String, submissionId: String) =
      firestore
          .collection(FirestoreCollections.USERS)
          .document(ownerId)
          .collection(InventorySubmissionFields.COLLECTION)
          .document(submissionId)

  override suspend fun updateItemImageIfAbsent(
      itemId: String,
      expectedName: String,
      image: ItemImage
  ): ItemImageUpdateResult {
    if (itemId.isBlank() || '/' in itemId) {
      return ItemImageUpdateResult.Error(IllegalArgumentException("A document ID is required"))
    }
    return try {
      val document = items.document(itemId)
      firestore
          .runTransaction { transaction ->
            val snapshot = transaction.get(document)
            if (!snapshot.exists() ||
                snapshot.getString(ItemFields.NAME) != expectedName ||
                snapshot.get(ItemFields.IMAGE) != null) {
              ItemImageUpdateResult.Ignored
            } else {
              transaction.update(document, ItemFields.IMAGE, image)
              ItemImageUpdateResult.Updated
            }
          }
          .await()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      ItemImageUpdateResult.Error(failure)
    }
  }

  override suspend fun deleteItem(itemId: String): Boolean {
    return when (val write = deleteItemQueued(itemId)) {
      is ItemWrite.Rejected -> false
      is ItemWrite.Queued -> write.confirmation.await().isSuccess
    }
  }

  override fun updateItem(itemId: String, edit: ItemEdit): ItemWrite {
    if (edit.name.isBlank() || edit.quantity <= 0) {
      return ItemWrite.Rejected(IllegalArgumentException("Name and positive quantity are required"))
    }
    if (!itemTextsFit(edit.name, edit.category)) {
      return ItemWrite.Rejected(IllegalArgumentException("Name or category is too long"))
    }
    val fields =
        mutableMapOf<String, Any?>(
            ItemFields.NAME to edit.name.trim(),
            ItemFields.QUANTITY to edit.quantity,
            ItemFields.CATEGORY to edit.category,
            ItemFields.EXPIRATION_DATE to edit.expirationDate)
    edit.sharing?.let {
      fields[ItemFields.SHARING] = it.name
      fields[ItemFields.LEGACY_SHARED] = FieldValue.delete()
    }
    return submit(itemId) { items.document(itemId).update(fields) }
  }

  override fun setItemSharing(itemId: String, sharing: ItemSharing): ItemWrite =
      submit(itemId) {
        items
            .document(itemId)
            .update(
                mapOf(
                    ItemFields.SHARING to sharing.name,
                    ItemFields.LEGACY_SHARED to FieldValue.delete()))
      }

  override fun setItemStatus(itemId: String, status: ItemStatus): ItemWrite =
      submit(itemId) { items.document(itemId).update(ItemFields.STATUS, status.name) }

  override fun deleteItemQueued(itemId: String): ItemWrite =
      submit(itemId) { items.document(itemId).delete() }

  private fun submit(itemId: String, write: () -> Task<Void>): ItemWrite {
    if (itemId.isBlank() || '/' in itemId) {
      return ItemWrite.Rejected(IllegalArgumentException("A document ID is required"))
    }
    return try {
      val task = write()
      if (task.isComplete && !task.isSuccessful) {
        ItemWrite.Rejected(task.exception ?: IllegalStateException("Write cancelled"))
      } else {
        val confirmation = CompletableDeferred<Result<Unit>>()
        task.addOnCompleteListener(Executor { it.run() }) { completed ->
          confirmation.complete(
              if (completed.isSuccessful) Result.success(Unit)
              else Result.failure(completed.exception ?: IllegalStateException("Write cancelled")))
        }
        ItemWrite.Queued(confirmation)
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      ItemWrite.Rejected(failure)
    }
  }

  private fun observe(
      query: Query,
      transform: (List<Item>) -> List<Item> = { it },
  ): Flow<List<Item>> =
      callbackFlow {
            val registration =
                query.addSnapshotListener { snapshot, error ->
                  if (error != null) {
                    close(error)
                    return@addSnapshotListener
                  }
                  // `snapshot` is nullable in Firestore's callback (though never null when there is
                  // no error).
                  // `mapNotNull` converts each document to an Item and drops invalid ones
                  // (toItemOrNull() returns null when name or ownerId is missing/blank).
                  // `orEmpty()` turns a null result (null snapshot) into an empty list,
                  // so we always end up with a non-null List<Item>.
                  val parsed = snapshot?.documents?.mapNotNull { it.toItemOrNull() }.orEmpty()
                  trySend(transform(parsed))
                }
            awaitClose { registration.remove() }
          }
          .distinctUntilChanged()
}

internal fun DocumentSnapshot.toItemOrNull(): Item? =
    try {
      val name = getString(ItemFields.NAME)?.takeIf { it.isNotBlank() }
      val ownerId = getString(ItemFields.OWNER_ID)?.takeIf { it.isNotBlank() }
      val quantity =
          when (val rawQuantity = get(ItemFields.QUANTITY)) {
            is Long -> rawQuantity.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
            is Int -> rawQuantity.takeIf { it > 0 }
            else -> null
          }
      val status = ItemStatus.values().firstOrNull { it.name == get(ItemFields.STATUS) }
      val householdId = getString(ItemFields.HOUSEHOLD_ID)?.takeIf { it.isNotBlank() }
      val sharing = ItemSharing.values().firstOrNull { it.name == get(ItemFields.SHARING) }
      // Items without a household predate the rule that every item belongs to one; they are
      // skipped rather than guessed at.
      if (name == null ||
          ownerId == null ||
          householdId == null ||
          quantity == null ||
          status == null ||
          sharing == null)
          null
      else
          Item(
              id = id,
              name = name,
              ownerId = ownerId,
              householdId = householdId,
              expirationDate = getTimestamp(ItemFields.EXPIRATION_DATE)?.toDate(),
              category = getString(ItemFields.CATEGORY)?.takeIf { it.isNotBlank() },
              image = getItemImageOrNull(),
              quantity = quantity,
              status = status,
              sharing = sharing)
    } catch (_: RuntimeException) {
      // A malformed document must not terminate a flow containing other valid items.
      null
    }

private fun DocumentSnapshot.getItemImageOrNull(): ItemImage? {
  val values = get(ItemFields.IMAGE) as? Map<*, *> ?: return null
  val origin =
      (values[ItemImageFields.ORIGIN] as? String)?.let {
        runCatching { ItemImageOrigin.valueOf(it) }.getOrNull()
      } ?: return null
  val reference =
      (values[ItemImageFields.REFERENCE] as? String)?.takeIf { it.isNotBlank() } ?: return null

  return ItemImage(
      origin = origin,
      reference = reference,
      author = values.optionalImageString(ItemImageFields.AUTHOR),
      sourceName = values.optionalImageString(ItemImageFields.SOURCE_NAME),
      sourceUrl = values.optionalImageString(ItemImageFields.SOURCE_URL),
      title = values.optionalImageString(ItemImageFields.TITLE),
      license = values.optionalImageString(ItemImageFields.LICENSE),
      licenseUrl = values.optionalImageString(ItemImageFields.LICENSE_URL),
      licenseVersion = values.optionalImageString(ItemImageFields.LICENSE_VERSION),
      authorUrl = values.optionalImageString(ItemImageFields.AUTHOR_URL))
}

private fun Map<*, *>.optionalImageString(key: String): String? =
    (this[key] as? String)?.takeIf { it.isNotBlank() }

private fun Item.hasValidTexts(): Boolean = itemTextsFit(name, category)

/**
 * The trimmed [name] and [category] are within [TextLimits], as `firestore.rules` also requires.
 */
private fun itemTextsFit(name: String, category: String?): Boolean =
    !name.trim().exceeds(TextLimits.ITEM_NAME) &&
        category?.trim()?.exceeds(TextLimits.ITEM_CATEGORY) != true

/** Fields of an item document in Firestore. */
internal object ItemFields {
  const val NAME = "name"
  const val OWNER_ID = "ownerId"
  const val HOUSEHOLD_ID = "householdId"
  const val QUANTITY = "quantity"
  const val EXPIRATION_DATE = "expirationDate"
  const val CATEGORY = "category"
  const val STATUS = "status"
  const val SHARING = "sharing"
  const val LEGACY_SHARED = "shared"
  const val IMAGE = "image"
}

/** Fields of the nested image in an item document. */
internal object ItemImageFields {
  const val ORIGIN = "origin"
  const val REFERENCE = "reference"
  const val AUTHOR = "author"
  const val SOURCE_NAME = "sourceName"
  const val SOURCE_URL = "sourceUrl"
  const val TITLE = "title"
  const val LICENSE = "license"
  const val LICENSE_URL = "licenseUrl"
  const val LICENSE_VERSION = "licenseVersion"
  const val AUTHOR_URL = "authorUrl"
}
