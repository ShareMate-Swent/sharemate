// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.navigation

enum class TopLevelDestination(
    val route: String,
    val textId: String,
    val testTag: String,
) {
  FRIDGE("fridge", "Fridge", NavigationTestTags.FRIDGE_TAB),
  RECIPES("recipes", "Recipes", NavigationTestTags.RECIPES_TAB),
  RECEIPTS("receipts", "Receipts", NavigationTestTags.RECEIPTS_TAB),
  CHAT("chat", "Chat", NavigationTestTags.CHAT_TAB),
  SETTINGS("settings", "Settings", NavigationTestTags.SETTINGS_TAB);

  /** Whether this tab should show as selected while [route] is the current screen. */
  fun isSelected(route: String?): Boolean = route == this.route || isParentOf(route)

  /** Whether [route] is a screen opened from this tab, named `<tab route>/<screen>`. */
  fun isParentOf(route: String?): Boolean = route?.startsWith("${this.route}/") == true
}

/** Screens opened from a tab. Each route starts with its tab's route, so the tab stays selected. */
object ChildRoutes {
  const val HOUSEHOLD_MEMBERS = "settings/members"
}
