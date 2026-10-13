// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import com.android.sharemate.ui.navigation.TopLevelDestination
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FridgeNavigationCleanupTest {
  private val controller = mock(NavHostController::class.java)

  @Test
  fun missingFridgeEntryStillClearsSavedNavigationWithoutThrowing() {
    prepareGraph()
    `when`(controller.getBackStackEntry(TopLevelDestination.FRIDGE.route))
        .thenThrow(IllegalArgumentException("Fridge is no longer on the back stack"))

    clearFridgeNavigation(controller)

    verifyNavigationCleared()
  }

  @Test
  fun existingFridgeEntryClearsItsViewModelAndSavedNavigation() {
    prepareGraph()
    val entry = mock(NavBackStackEntry::class.java)
    val lifecycle = mock(Lifecycle::class.java)
    val store = ViewModelStore()
    val viewModel = RecordingViewModel()
    store.put("fridge", viewModel)
    `when`(controller.getBackStackEntry(TopLevelDestination.FRIDGE.route)).thenReturn(entry)
    `when`(entry.lifecycle).thenReturn(lifecycle)
    `when`(lifecycle.currentState).thenReturn(Lifecycle.State.CREATED)
    `when`(entry.viewModelStore).thenReturn(store)

    clearFridgeNavigation(controller)

    assertTrue(viewModel.cleared)
    assertTrue(store.keys().isEmpty())
    verifyNavigationCleared()
  }

  private fun prepareGraph() {
    val graph = mock(NavGraph::class.java)
    `when`(graph.id).thenReturn(42)
    `when`(controller.graph).thenReturn(graph)
  }

  private fun verifyNavigationCleared() {
    TopLevelDestination.entries.forEach { verify(controller).clearBackStack(it.route) }
    verify(controller).popBackStack(42, true)
  }

  private class RecordingViewModel : ViewModel() {
    var cleared = false

    override fun onCleared() {
      cleared = true
    }
  }
}
