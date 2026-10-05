package com.android.sharemate.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationViewModelTest {
  @Test
  fun startsWithFridgeSelected() {
    val viewModel = NavigationViewModel()

    assertEquals(TopLevelDestination.FRIDGE, viewModel.selectedDestination.value)
  }

  @Test
  fun selectDestinationUpdatesSelectedDestination() {
    val viewModel = NavigationViewModel()

    TopLevelDestination.entries.forEach { destination ->
      viewModel.selectDestination(destination)

      assertEquals(destination, viewModel.selectedDestination.value)
    }
  }
}
