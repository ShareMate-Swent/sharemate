// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.barcode.BarcodeScanResult
import com.android.sharemate.model.barcode.BarcodeScanner
import com.android.sharemate.model.image.FoodImage
import com.android.sharemate.model.image.FoodImageRepository
import com.android.sharemate.model.image.FoodImageSearchResult
import com.android.sharemate.model.image.FoodImageSelection
import com.android.sharemate.model.image.FoodImageSelector
import com.android.sharemate.model.item.FirebaseItemRepository
import com.android.sharemate.model.item.ItemImage
import com.android.sharemate.model.item.toItemOrNull
import com.android.sharemate.utils.FirebaseEmulator
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Full creation/enrichment/persistence path with real Firebase and a gated image service. */
@RunWith(AndroidJUnit4::class)
class FridgeImageFirestoreTest {
  private val firestore = FirebaseEmulator.firestore
  private val store = ViewModelStore()
  private val lookup = CompletableDeferred<FoodImageSearchResult>()

  @Before
  fun setUp() {
    FirebaseEmulator.clear()
  }

  @After
  fun tearDown() = runBlocking {
    withContext(Dispatchers.Main) { store.clear() }
    lookup.cancel()
    FirebaseEmulator.clear()
  }

  @Test
  fun temporaryImageServiceFailureRecoversWithImageAndFirestoreReread() = runBlocking {
    val owner = FirebaseEmulator.signInAs("image-creation-owner")
    val repository = FirebaseItemRepository(firestore)
    var lookupCalls = 0
    val selector =
        FoodImageSelector(
            object : FoodImageRepository {
              override suspend fun searchByTitle(title: String): FoodImageSearchResult {
                assertEquals("Milk", title)
                if (++lookupCalls == 1) {
                  return FoodImageSearchResult.Failure(FoodImageSearchResult.Reason.HTTP, 504)
                }
                return lookup.await()
              }
            })
    val vm =
        withContext(Dispatchers.Main) {
          FridgeViewModel(
                  repository, owner, null, selector, BarcodeScanner { BarcodeScanResult.NotFound })
              .also { store.put("fridge", it) }
        }
    withContext(Dispatchers.Main) {
      vm.openAddItemDialog()
      vm.updateName("  Milk  ")
      vm.updateCategory("Dairy")
      vm.updateQuantity("3")
      vm.saveItem()
    }
    val saved =
        withTimeout(15_000) {
          vm.uiState.first { !it.isAddItemDialogOpen && !it.isSaving && it.items.size == 1 }
        }
    val placeholder = saved.items.single()
    assertNull(placeholder.image)
    assertFalse(lookup.isCompleted)
    val doc = firestore.collection(FirestoreCollections.ITEMS).document(placeholder.id)
    assertEquals(placeholder, withTimeout(15_000) { doc.get(Source.SERVER).await() }.toItemOrNull())

    val result =
        FoodImageSearchResult.Found(
            FoodImage(
                url = "https://images.example.org/milk.jpg",
                author = "Jane Doe",
                sourceName = "Photo library",
                sourceUrl = "https://example.org/photos/42",
                title = "Fresh milk",
                license = "by",
                licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
                licenseVersion = "4.0",
                authorUrl = "https://example.org/jane"))
    lookup.complete(result)
    val expectedImage = ItemImage.fromSelection(FoodImageSelection.RemoteImage(result.image))
    val enriched =
        withTimeout(15_000) { vm.uiState.first { it.items.singleOrNull()?.image != null } }
    assertEquals(placeholder.copy(image = expectedImage), enriched.items.single())
    assertEquals(2, lookupCalls)
    assertFalse(enriched.isAddItemDialogOpen)
    assertNull(enriched.formError)
    // Clear the original ViewModel: a new repository and a server read must recover the image.
    withContext(Dispatchers.Main) { store.clear() }
    val reread =
        withTimeout(15_000) {
          FirebaseItemRepository(firestore).getPrivateItems(owner).first {
            it.singleOrNull()?.image != null
          }
        }
    assertEquals(enriched.items, reread)
    assertEquals(
        reread.single(), withTimeout(15_000) { doc.get(Source.SERVER).await() }.toItemOrNull())
  }
}
