package com.android.sharemate.ui.navigation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TopLevelDestination(
    val route: String,
    val textId: String,
    val testTag: String,
) {
  FRIDGE(Route.FRIDGE, "Fridge", NavigationTestTags.FRIDGE_TAB),
  RECIPES(Route.RECIPES, "Recipes", NavigationTestTags.RECIPES_TAB),
  RECEIPTS(Route.RECEIPTS, "Receipts", NavigationTestTags.RECEIPTS_TAB),
  SETTINGS(Route.SETTINGS, "Settings", NavigationTestTags.SETTINGS_TAB),
}

class NavigationViewModel : ViewModel() {
  private val _selectedDestination = MutableStateFlow(TopLevelDestination.FRIDGE)
  val selectedDestination: StateFlow<TopLevelDestination> = _selectedDestination.asStateFlow()

  fun selectDestination(destination: TopLevelDestination) {
    _selectedDestination.value = destination
  }
}
