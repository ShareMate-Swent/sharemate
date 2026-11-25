// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.navigation

import androidx.annotation.StringRes
import com.android.sharemate.R
import com.android.sharemate.model.household.FridgeType

enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val testTag: String,
) {
  FRIDGE("fridge", R.string.fridge_title, NavigationTestTags.FRIDGE_TAB),
  RECIPES("recipes", R.string.recipes_title, NavigationTestTags.RECIPES_TAB),
  RECEIPTS("receipts", R.string.receipts_title, NavigationTestTags.RECEIPTS_TAB),
  CHAT("chat", R.string.chat_title, NavigationTestTags.CHAT_TAB),
  SETTINGS("settings", R.string.settings_title, NavigationTestTags.PROFILE_ENTRY);

  /** Whether this tab should show as selected while [route] is the current screen. */
  fun isSelected(route: String?): Boolean = route == this.route || isParentOf(route)

  /** Whether [route] is a screen opened from this tab, named `<tab route>/<screen>`. */
  fun isParentOf(route: String?): Boolean = route?.startsWith("${this.route}/") == true

  /** Whether this tab is offered in a fridge of [type]. A private fridge has no chat. */
  fun isAvailableIn(type: FridgeType): Boolean = this != CHAT || type == FridgeType.SHARED

  companion object {
    /** The tabs of the bottom bar, in order. Settings opens from the profile photo instead. */
    val bottomBar = listOf(FRIDGE, RECIPES, RECEIPTS, CHAT)

    /** The tabs of the bottom bar, in order, offered in a fridge of [type]. */
    fun bottomBarFor(type: FridgeType): List<TopLevelDestination> =
        bottomBar.filter { it.isAvailableIn(type) }

    /**
     * Whether the bottom bar shows on [route]: only on its tabs and the screens opened from them,
     * so a new screen has no bar unless it belongs to one of them.
     */
    fun showsBottomBar(route: String?): Boolean = bottomBar.any { it.isSelected(route) }
  }
}

/** Screens opened from a tab. Each route starts with its tab's route, so the tab stays selected. */
object ChildRoutes {
  const val HOUSEHOLD_MEMBERS = "settings/members"

  /** The notification settings sub-page of Settings. */
  const val NOTIFICATION_SETTINGS = "settings/notifications"

  /** Key scoping the notification settings ViewModel to one user. */
  fun notificationSettingsViewModelKey(userId: String) = "notification_settings_$userId"
}
