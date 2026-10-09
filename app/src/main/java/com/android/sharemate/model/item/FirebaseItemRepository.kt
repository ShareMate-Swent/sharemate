package com.android.sharemate.model.item

import com.android.sharemate.model.FirestoreCollections
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CancellationException
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
        observe(items.whereEqualTo("ownerId", userId)) {
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
    if (item.name.isBlank() || item.ownerId.isBlank()) return null
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
    if (itemId.isBlank()) return false
    return try {
      items.document(itemId).delete().await()
      true
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      false
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

internal fun DocumentSnapshot.toItemOrNull(): Item? {
  val name = getString("name")?.takeIf { it.isNotBlank() } ?: return null
  val ownerId = getString("ownerId")?.takeIf { it.isNotBlank() } ?: return null
  return Item(
      id = id,
      name = name,
      ownerId = ownerId,
      householdId = getString("householdId")?.takeIf { it.isNotBlank() },
      expirationDate = getTimestamp("expirationDate")?.toDate(),
      category = getString("category")?.takeIf { it.isNotBlank() })
}
