// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.image

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.request.ErrorResult
import coil.request.SuccessResult
import com.android.sharemate.AuthenticatedUserRule
import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.barcode.BarcodeScanResult
import com.android.sharemate.model.barcode.BarcodeScanner
import com.android.sharemate.model.item.FirebaseItemRepository
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.toItemOrNull
import com.android.sharemate.ui.fridge.FridgeViewModel
import com.android.sharemate.ui.fridge.fridgeImageRequest
import com.android.sharemate.ui.fridge.testProductCatalogRepository
import com.android.sharemate.utils.FirebaseEmulator
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Firebase emulators required. Normal runs use a fixed response and never contact Commons. */
@RunWith(AndroidJUnit4::class)
class CommonsImageFirestoreTest {
  @get:Rule val authenticatedUser = AuthenticatedUserRule()

  @Test
  fun commonsImageAndCreditsSurviveCreationAndFirestoreReread() = runBlocking {
    val client =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
              Response.Builder()
                  .request(chain.request())
                  .protocol(Protocol.HTTP_1_1)
                  .code(200)
                  .message("Controlled Commons response")
                  .body(COMMONS_RESPONSE.toResponseBody())
                  .build()
            }
            .build()
    try {
      val saved = createAndReload(CommonsFoodImageRepository(client))
      val image = checkNotNull(saved.image)
      assertEquals("by-sa", image.license)
      assertEquals("4.0", image.licenseVersion)
      assertEquals("Test photographer", image.author)
      assertEquals("https://creativecommons.org/licenses/by-sa/4.0/", image.licenseUrl)
    } finally {
      client.connectionPool.evictAll()
      client.dispatcher.executorService.shutdownNow()
    }
  }

  /** Opt-in manual smoke test: -Pandroid.testInstrumentationRunnerArguments.commonsLive=true. */
  @Test
  fun liveCommonsImageCanBeDecodedAndRereadWhenExplicitlyEnabled() = runBlocking {
    assumeTrue(InstrumentationRegistry.getArguments().getString("commonsLive") == "true")
    val saved = createAndReload(CommonsFoodImageRepository())
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val loader = ImageLoader(context)
    try {
      val decoded =
          withTimeout(20_000) {
            loader.execute(fridgeImageRequest(context, saved.image!!.reference, 80))
          }
      assertTrue(
          "Thumbnail decode failed: ${(decoded as? ErrorResult)?.throwable}",
          decoded is SuccessResult)
    } finally {
      loader.shutdown()
    }
  }

  private suspend fun createAndReload(images: FoodImageRepository): Item {
    val firestore = FirebaseEmulator.firestore
    firestore.enableNetwork().await()
    val userId = checkNotNull(FirebaseEmulator.auth.uid)
    val repository = FirebaseItemRepository(firestore)
    val store = ViewModelStore()
    val vm =
        withContext(Dispatchers.Main) {
          FridgeViewModel(
                  repository,
                  userId,
                  null,
                  FoodImageSelector(images),
                  BarcodeScanner { BarcodeScanResult.NotFound },
                  testProductCatalogRepository())
              .also { store.put("commons", it) }
        }
    var id: String? = null
    try {
      withTimeout(10_000) { vm.uiState.first { !it.isLoading } }
      withContext(Dispatchers.Main) {
        vm.openAddItemDialog()
        vm.updateName("tacos")
        vm.updateQuantity("3")
        vm.updateCategory("Prepared food")
        vm.saveItem()
      }
      val state =
          withTimeout(30_000) { vm.uiState.first { !it.isAddItemDialogOpen && !it.isSaving } }
      assertFalse(state.loadFailed)
      val saved = state.items.single { it.name == "tacos" }
      id = saved.id
      assertNotNull("Commons must return an image in this test", saved.image)
      val image = checkNotNull(saved.image)
      assertEquals("Wikimedia Commons", image.sourceName)
      assertTrue(image.reference.startsWith("https://"))
      assertNotNull(image.licenseUrl)
      assertEquals(3, saved.quantity)
      assertEquals("Prepared food", saved.category)
      assertEquals(userId, saved.ownerId)

      val document = firestore.collection(FirestoreCollections.ITEMS).document(saved.id)
      assertEquals(saved, document.get(Source.SERVER).await().toItemOrNull())
      withContext(Dispatchers.Main) { store.clear() }
      val reloaded =
          withTimeout(10_000) {
            FirebaseItemRepository(firestore)
                .getPrivateItems(userId)
                .first { items -> items.any { it.id == saved.id } }
                .single { it.id == saved.id }
          }
      assertEquals(saved, reloaded)
      return reloaded
    } finally {
      withContext(Dispatchers.Main) { store.clear() }
      id?.let { firestore.collection(FirestoreCollections.ITEMS).document(it).delete().await() }
    }
  }

  private companion object {
    const val COMMONS_RESPONSE =
        """{
      "batchcomplete":true,
      "query":{"pages":[{
        "title":"File:Tacos.jpg","index":1,
        "imageinfo":[{
          "thumburl":"https://images.example.org/tacos.jpg",
          "mime":"image/jpeg",
          "descriptionurl":"https://commons.wikimedia.org/wiki/File:Tacos.jpg",
          "extmetadata":{
            "Artist":{"value":"Test photographer"},
            "ObjectName":{"value":"Tacos"},
            "LicenseShortName":{"value":"CC BY-SA 4.0"},
            "LicenseUrl":{"value":"https://creativecommons.org/licenses/by-sa/4.0/"}
          }
        }]
      }]}
    }"""
  }
}
