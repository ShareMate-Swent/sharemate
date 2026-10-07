// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.item

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

class FakeItemRepository : ItemRepository {
  private val items = MutableStateFlow<List<Item>>(emptyList())
  private var nextId = 0
  val addedItems = mutableListOf<Item>()
  val requestedHouseholdIds = mutableListOf<String>()
  val requestedUserIds = mutableListOf<String>()
  var returnNullOnAdd = false
  var addFailure: Exception? = null
  var readFailure: Exception? = null
  var addGate: CompletableDeferred<Unit>? = null

  override fun getPrivateItems(userId: String): Flow<List<Item>> {
    requestedUserIds += userId
    return items.map { current -> current.filter { it.ownerId == userId && !it.isShared } }
  }

  override fun getSharedItems(householdId: String): Flow<List<Item>> {
    requestedHouseholdIds += householdId
    readFailure?.let { failure ->
      return flow { throw failure }
    }
    return items.map { current -> current.filter { it.householdId == householdId } }
  }

  override suspend fun addItem(item: Item): String? {
    addedItems += item
    addGate?.await()
    addFailure?.let { throw it }
    if (returnNullOnAdd) return null
    val itemId = "item-${++nextId}"
    items.value += item.copy(id = itemId)
    return itemId
  }

  override suspend fun deleteItem(itemId: String): Boolean {
    val existed = items.value.any { it.id == itemId }
    items.value = items.value.filterNot { it.id == itemId }
    return existed
  }

  fun emitItems(updatedItems: List<Item>) {
    items.value = updatedItems
  }
}
