// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.item

import com.android.sharemate.model.FirestoreCollections
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
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

  override fun getPrivateItems(userId: String): Flow<List<Item>> =
      if (userId.isBlank()) {
        flowOf(emptyList())
      } else {
        observe(items.whereEqualTo("ownerId", userId).whereEqualTo("householdId", null)) {
          it.filter { item -> item.householdId == null }
        }
      }

  override fun getSharedItems(householdId: String): Flow<List<Item>> =
      if (householdId.isBlank()) {
        flowOf(emptyList())
      } else {
        observe(items.whereEqualTo("householdId", householdId))
      }

  override suspend fun addItem(item: Item): String? {
    if (item.name.isBlank() || item.ownerId.isBlank() || item.quantity <= 0) return null
    return try {
      val document = items.document()
      val savedItem =
          item.copy(
              id = document.id,
              name = item.name.trim(),
              householdId = item.householdId?.takeIf { it.isNotBlank() })
      document.set(savedItem).await()
      document.id
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      null
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
    return submit(itemId) {
      items
          .document(itemId)
          .update(
              mapOf(
                  "name" to edit.name.trim(),
                  "quantity" to edit.quantity,
                  "category" to edit.category,
                  "expirationDate" to edit.expirationDate))
    }
  }

  override fun setItemStatus(itemId: String, status: ItemStatus): ItemWrite =
      submit(itemId) { items.document(itemId).update("status", status.name) }

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
      val name = getString("name")?.takeIf { it.isNotBlank() }
      val ownerId = getString("ownerId")?.takeIf { it.isNotBlank() }
      val rawQuantity = get("quantity")
      val quantity =
          when (rawQuantity) {
            null -> if (contains("quantity")) null else 1
            is Long -> rawQuantity.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
            is Int -> rawQuantity.takeIf { it > 0 }
            else -> null
          }
      val status =
          if (!contains("status")) ItemStatus.ACTIVE
          else ItemStatus.values().firstOrNull { it.name == get("status") }
      if (name == null || ownerId == null || quantity == null || status == null) null
      else
          Item(
              id = id,
              name = name,
              ownerId = ownerId,
              householdId = getString("householdId")?.takeIf { it.isNotBlank() },
              expirationDate = getTimestamp("expirationDate")?.toDate(),
              category = getString("category"),
              quantity = quantity,
              status = status)
    } catch (_: RuntimeException) {
      // A malformed document must not terminate a flow containing other valid items.
      null
    }
