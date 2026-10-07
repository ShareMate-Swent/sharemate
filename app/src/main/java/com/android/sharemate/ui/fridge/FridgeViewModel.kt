package com.android.sharemate.ui.fridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class FridgeSortOrder {
  EXPIRATION_DATE,
  NAME,
}

class FridgeViewModel(
    itemRepository: ItemRepository,
    householdId: String,
) : ViewModel() {
  private val sortOrderState = MutableStateFlow(FridgeSortOrder.EXPIRATION_DATE)
  private val categoryFilterState = MutableStateFlow<String?>(null)
  private val ownerFilterState = MutableStateFlow<String?>(null)

  val sortOrder: StateFlow<FridgeSortOrder> = sortOrderState
  val selectedCategory: StateFlow<String?> = categoryFilterState
  val selectedOwnerId: StateFlow<String?> = ownerFilterState

  val visibleItems: StateFlow<List<Item>> =
      combine(
              itemRepository.getSharedItems(householdId),
              sortOrderState,
              categoryFilterState,
              ownerFilterState,
          ) { items, sortOrder, category, ownerId ->
            val filteredItems =
                items.filter { item ->
                  (category == null ||
                      item.category?.trim()?.equals(category, ignoreCase = true) == true) &&
                      (ownerId == null || item.ownerId == ownerId)
                }

            when (sortOrder) {
              FridgeSortOrder.EXPIRATION_DATE ->
                  filteredItems.sortedWith(
                      compareBy<Item> { it.expirationDate == null }.thenBy { it.expirationDate })
              FridgeSortOrder.NAME ->
                  filteredItems.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            }
          }
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(5_000),
              initialValue = emptyList(),
          )

  fun setSortOrder(sortOrder: FridgeSortOrder) {
    sortOrderState.value = sortOrder
  }

  fun setCategoryFilter(category: String?) {
    categoryFilterState.value = category.normalizedFilter()
  }

  fun setOwnerFilter(ownerId: String?) {
    ownerFilterState.value = ownerId.normalizedFilter()
  }

  private fun String?.normalizedFilter(): String? = this?.trim()?.takeIf(String::isNotEmpty)
}
