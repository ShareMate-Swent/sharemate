package com.android.sharemate.ui.navigation

enum class TopLevelDestination(
    val route: String,
    val textId: String,
    val testTag: String,
) {
  FRIDGE("fridge", "Fridge", NavigationTestTags.FRIDGE_TAB),
  RECIPES("recipes", "Recipes", NavigationTestTags.RECIPES_TAB),
  RECEIPTS("receipts", "Receipts", NavigationTestTags.RECEIPTS_TAB),
  SETTINGS("settings", "Settings", NavigationTestTags.SETTINGS_TAB),
}
