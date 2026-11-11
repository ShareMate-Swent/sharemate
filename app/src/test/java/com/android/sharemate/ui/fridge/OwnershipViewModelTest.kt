// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.image.*
import com.android.sharemate.model.item.*
import com.android.sharemate.model.user.ExpirationPreferencesProvider
import com.android.sharemate.model.user.FakeExpirationPreferencesRepository
import com.android.sharemate.model.user.MemberNameRepository
import com.android.sharemate.model.user.PseudoRepository
import java.util.Date
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnershipViewModelTest {
  private val store = ViewModelStore()
  private val items = FakeItemRepository()
  private val names = MutableStateFlow(mapOf("alice" to "Alice", "bob" to "Bob"))
  private var subscriptions = 0
  private var publications = 0
  private val publishedNames = mutableListOf<String?>()
  private var failNames = false
  private var failPublish = false
  private var closed = false
  private val directory =
      object : MemberNameRepository {
        override fun observeNames(householdId: String, viewerId: String) = flow {
          assertEquals("home", householdId)
          assertEquals("alice", viewerId)
          subscriptions++
          try {
            if (failNames) error("Denied")
            emitAll(names)
          } finally {
            closed = true
          }
        }

        override suspend fun publishOwnName(
            householdId: String,
            userId: String,
            authName: String?
        ) {
          publications++
          publishedNames += authName
          assertEquals("home", householdId)
          assertEquals("alice", userId)
          if (failPublish) error("Unavailable")
        }
      }

  @Before
  fun setup() {
    Dispatchers.setMain(StandardTestDispatcher())
  }

  @After
  fun cleanup() {
    store.clear()
    Dispatchers.resetMain()
  }

  private fun vm(
      household: String = "home",
      name: String? = null,
      type: FridgeType = FridgeType.SHARED,
      pseudos: PseudoRepository? = null
  ): FridgeViewModel =
      FridgeViewModel(
              items,
              "alice",
              household,
              FoodImageSelector(
                  object : FoodImageRepository {
                    override suspend fun searchByTitle(title: String) =
                        FoodImageSearchResult.NotFound
                  }),
              testBarcodeScanner(),
              testProductCatalogRepository(),
              expirationPreferences =
                  ExpirationPreferencesProvider(FakeExpirationPreferencesRepository()),
              fridgeType = type,
              pseudoRepository = pseudos,
              memberNameRepository = directory,
              currentUserDisplayName = name)
          .also { store.put("fridge", it) }

  @Test
  fun memberNamesUpdateWithoutReloadingInventoryOrSubscribingPerItem() = runTest {
    val inventory = (0..19).map { Item("$it", "Milk", "bob", "home", sharing = ItemSharing.SHARED) }
    items.emitItems(inventory)
    val vm = vm()
    runCurrent()
    assertEquals("Bob", vm.uiState.value.ownershipFor(inventory.first()).name)
    assertEquals(1, subscriptions)
    assertEquals(1, publications)
    names.value = mapOf("bob" to "Robert")
    runCurrent()
    assertEquals("Robert", vm.uiState.value.ownershipFor(inventory.last()).name)
    assertEquals(inventory, vm.uiState.value.items)
    assertEquals(1, items.requestedHouseholdIds.size)
    assertEquals(1, subscriptions)
    store.clear()
    runCurrent()
    assertTrue(closed)
  }

  @Test
  fun currentUserAndOtherMembersRemainDistinctAndEmailsAreNotNames() = runTest {
    val vm = vm(name = "Alice Auth")
    runCurrent()
    val own = Item("own", "Milk", "alice", "home", sharing = ItemSharing.PERSONAL)
    assertEquals(
        ItemOwnership(ItemSharing.PERSONAL, "Alice Auth", true), vm.uiState.value.ownershipFor(own))
    assertEquals(
        ItemOwnership(ItemSharing.PERSONAL, "Bob", false),
        vm.uiState.value.ownershipFor(own.copy(ownerId = "bob")))
    names.value = mapOf("bob" to "bob@example.com")
    runCurrent()
    assertNull(vm.uiState.value.ownershipFor(own.copy(ownerId = "bob")).name)
    assertNull(vm.uiState.value.ownershipFor(own.copy(ownerId = "former-member")).name)
  }

  @Test
  fun unavailableDirectoryAndPublicationNeverBlockInventory() = runTest {
    failNames = true
    failPublish = true
    val item = Item("milk", "Milk", "alice", "home")
    items.emitItems(listOf(item))
    val vm = vm(name = "Alice")
    runCurrent()
    assertFalse(vm.uiState.value.isLoading)
    assertFalse(vm.uiState.value.loadFailed)
    assertEquals(listOf(item), vm.uiState.value.items)
    assertEquals("Alice", vm.uiState.value.ownershipFor(item).name)
  }

  @Test
  fun privateFridgeUsesOnlyOwnAuthNameWithoutDirectoryQueries() = runTest {
    val item = Item("milk", "Milk", "alice", "home")
    items.emitItems(listOf(item))
    val vm = vm(name = "Alice", type = FridgeType.PRIVATE)
    runCurrent()
    assertEquals(0, subscriptions)
    assertEquals(0, publications)
    assertEquals(
        ItemOwnership(ItemSharing.PERSONAL, "Alice", true), vm.uiState.value.ownershipFor(item))
    assertEquals(listOf("alice"), items.requestedUserIds)
  }

  @Test
  fun legacyDefaultsAndExplicitSharingAreIndependentOfHouseholdAndOwner() {
    val state = FridgeUiState(fridgeType = FridgeType.SHARED, currentUserId = "alice")
    assertEquals(ItemSharing.PERSONAL, state.ownershipFor(Item(householdId = "home")).sharing)
    assertEquals(ItemSharing.PERSONAL, state.ownershipFor(Item(householdId = "home")).sharing)
    assertEquals(
        ItemSharing.PERSONAL,
        state.ownershipFor(Item(householdId = "home", sharing = ItemSharing.PERSONAL)).sharing)
    assertEquals(
        ItemSharing.SHARED,
        state.ownershipFor(Item(householdId = "home", sharing = ItemSharing.SHARED)).sharing)
  }

  @Test
  fun nameChangesPreserveCategoryOwnerFiltersAndSortOrder() = runTest {
    items.emitItems(
        listOf(
            Item(
                "z",
                "Zucchini",
                "bob",
                "home",
                category = "Veg",
                expirationDate = Date(1),
                sharing = ItemSharing.SHARED),
            Item("a", "Apple", "alice", "home", category = "Fruit", expirationDate = Date(2)),
            Item(
                "b",
                "Banana",
                "bob",
                "home",
                category = "Fruit",
                expirationDate = Date(3),
                sharing = ItemSharing.SHARED)))
    val vm = vm()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.visibleItems.collect() }
    runCurrent()
    assertEquals(listOf("z", "a", "b"), vm.visibleItems.value.map { it.id })
    vm.setCategoryFilter("Fruit")
    vm.setOwnerFilter("bob")
    vm.setSortOrder(FridgeSortOrder.NAME)
    runCurrent()
    assertEquals(listOf("b"), vm.visibleItems.value.map { it.id })
    names.value = mapOf("bob" to "Bobby")
    runCurrent()
    assertEquals(listOf("b"), vm.visibleItems.value.map { it.id })
    assertEquals("bob", vm.selectedOwnerId.value)
    assertEquals("Fruit", vm.selectedCategory.value)
    assertEquals(FridgeSortOrder.NAME, vm.sortOrder.value)
  }

  @Test
  fun ownPseudoUpdatesPublishWithoutReadingOtherProfilesAndFiltersUseDirectory() = runTest {
    val ownProfile = MutableStateFlow(mapOf("alice" to "New Alice"))
    val requested = mutableListOf<Set<String>>()
    val pseudos =
        object : PseudoRepository {
          override fun observePseudos(userIds: Set<String>): Flow<Map<String, String>> {
            requested += userIds
            return ownProfile
          }

          override suspend fun getPseudo(userId: String): String? = error("unused")

          override suspend fun setPseudo(userId: String, pseudo: String) = error("unused")
        }
    items.emitItems(listOf(Item("b", "Bread", "bob", "home", sharing = ItemSharing.SHARED)))
    val vm = vm(name = "Old provider name", pseudos = pseudos)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.ownerOptions.collect() }
    runCurrent()
    assertEquals(listOf(setOf("alice")), requested)
    assertEquals("New Alice", vm.uiState.value.ownerNames["alice"])
    assertEquals("Bob", vm.ownerOptions.value.single().pseudo)
    ownProfile.value = mapOf("alice" to "Renamed Alice")
    runCurrent()
    assertEquals("Renamed Alice", publishedNames.last())
    assertEquals("Renamed Alice", vm.uiState.value.ownerNames["alice"])
    names.value = emptyMap()
    runCurrent()
    assertNull(vm.ownerOptions.value.single().pseudo)
    store.clear()
    ownProfile.value = mapOf("alice" to "After cancellation")
    runCurrent()
    assertEquals("Renamed Alice", publishedNames.last())
  }

  @Test
  fun formResetsPreserveOwnerNamesIdentityAndOwnerFilter() = runTest {
    val item = Item("mine", "Milk", "alice", "home", sharing = ItemSharing.PERSONAL)
    items.emitItems(listOf(item))
    val vm = vm(name = "Alice")
    runCurrent()
    val ownerNames = vm.uiState.value.ownerNames
    vm.setOwnerFilter("bob")
    vm.openEditItemDialog(item.id)
    vm.updateSharing(ItemSharing.SHARED)
    vm.dismissAddItemDialog()
    assertEquals(ownerNames, vm.uiState.value.ownerNames)
    assertEquals("alice", vm.uiState.value.currentUserId)
    assertEquals("bob", vm.selectedOwnerId.value)
    assertNull(vm.uiState.value.originalItemSharing)
    assertFalse(vm.uiState.value.canChangeSharing)
    assertTrue(items.edits.isEmpty())
    vm.openEditItemDialog(item.id)
    vm.updateSharing(ItemSharing.SHARED)
    vm.saveItem()
    runCurrent()
    assertEquals(ItemSharing.SHARED, vm.uiState.value.items.single().sharing)
    assertEquals(ownerNames, vm.uiState.value.ownerNames)
    assertEquals(
        ItemOwnership(ItemSharing.SHARED, "Alice", true),
        vm.uiState.value.ownershipFor(item.copy(sharing = ItemSharing.SHARED)))
    assertEquals("bob", vm.selectedOwnerId.value)
    assertNull(vm.uiState.value.originalItemSharing)
    assertFalse(vm.uiState.value.canChangeSharing)
  }
}
