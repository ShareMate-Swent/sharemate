// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.android.sharemate.model.barcode.BarcodeScanResult
import com.android.sharemate.model.barcode.BarcodeScanner
import com.android.sharemate.model.image.FoodImage
import com.android.sharemate.model.image.FoodImageRepository
import com.android.sharemate.model.image.FoodImageSearchResult
import com.android.sharemate.model.image.FoodImageSelector
import com.android.sharemate.model.item.FakeItemRepository
import com.android.sharemate.ui.fridge.FridgeViewModel
import com.android.sharemate.ui.navigation.TopLevelDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises Navigation's saved entries and the production session cleanup, without dialog layout.
 */
@RunWith(RobolectricTestRunner::class)
class FridgeImageNavigationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val signedIn = mutableStateOf(true)
  private val repository = FakeItemRepository()
  private val lookup = CompletableDeferred<FoodImageSearchResult>()
  private val cancelled = CompletableDeferred<Unit>()
  private var calls = 0
  private lateinit var controller: NavHostController
  private lateinit var fridge: FridgeViewModel

  @Test
  fun lookupCompletesOnAnotherTabAndReturningDoesNotRestartIt() {
    showNavigation()
    createMilk()
    val original = fridge
    navigate(TopLevelDestination.RECIPES)
    val image =
        FoodImage(
            url = "https://example.org/milk.jpg",
            author = null,
            sourceName = null,
            sourceUrl = "https://example.org/source",
            title = null,
            license = "cc0",
            licenseUrl = "https://creativecommons.org/publicdomain/zero/1.0/",
            licenseVersion = "1.0",
            authorUrl = null)
    compose.runOnIdle {
      assertFalse(cancelled.isCompleted)
      lookup.complete(FoodImageSearchResult.Found(image))
    }
    compose.waitUntil { repository.imageUpdates.size == 1 }
    navigate(TopLevelDestination.FRIDGE)
    compose.runOnIdle {
      assertSame(original, fridge)
      assertEquals(image.url, fridge.uiState.value.items.single().image?.reference)
      assertEquals(1, calls)
    }
  }

  @Test
  fun sessionTeardownFromAnotherTabCancelsLookupWithoutUndoingCreation() {
    showNavigation()
    createMilk()
    navigate(TopLevelDestination.RECIPES)
    compose.runOnIdle {
      assertFalse(cancelled.isCompleted)
      signedIn.value = false
    }
    compose.onNodeWithText("Signed out").assertIsDisplayed()
    compose.waitUntil { cancelled.isCompleted }
    compose.runOnIdle {
      lookup.complete(FoodImageSearchResult.NotFound)
      assertEquals(1, repository.addedItems.size)
      assertTrue(repository.imageUpdates.isEmpty())
    }
  }

  private fun createMilk() {
    compose.runOnIdle {
      fridge.openAddItemDialog()
      fridge.updateName("Milk")
      fridge.saveItem()
    }
    compose.waitUntil { calls == 1 && fridge.uiState.value.items.size == 1 }
    compose.runOnIdle {
      assertFalse(fridge.uiState.value.isAddItemDialogOpen)
      assertNull(fridge.uiState.value.items.single().image)
    }
  }

  private fun navigate(destination: TopLevelDestination) =
      compose.runOnIdle {
        controller.navigate(destination.route) {
          popUpTo(controller.graph.findStartDestination().id) { saveState = true }
          launchSingleTop = true
          restoreState = true
        }
      }

  private fun showNavigation() {
    val selector =
        FoodImageSelector(
            object : FoodImageRepository {
              override suspend fun searchByTitle(title: String): FoodImageSearchResult {
                calls++
                return try {
                  lookup.await()
                } catch (e: CancellationException) {
                  cancelled.complete(Unit)
                  throw e
                }
              }
            })
    compose.setContent {
      if (signedIn.value) {
        controller = rememberNavController()
        DisposableEffect(controller) { onDispose { clearFridgeNavigation(controller) } }
        NavHost(controller, startDestination = TopLevelDestination.FRIDGE.route) {
          composable(TopLevelDestination.FRIDGE.route) { entry ->
            fridge =
                viewModel(
                    viewModelStoreOwner = entry,
                    factory =
                        viewModelFactory {
                          initializer {
                            FridgeViewModel(
                                repository,
                                "owner",
                                "home",
                                selector,
                                BarcodeScanner { BarcodeScanResult.NotFound })
                          }
                        })
            Text("Fridge")
          }
          composable(TopLevelDestination.RECIPES.route) { Text("Recipes") }
          composable(TopLevelDestination.RECEIPTS.route) { Text("Receipts") }
          composable(TopLevelDestination.SETTINGS.route) { Text("Settings") }
        }
      } else Text("Signed out")
    }
  }
}
