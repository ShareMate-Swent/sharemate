// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.navigation

import androidx.annotation.StringRes
import com.android.sharemate.R

enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val testTag: String,
) {
  FRIDGE("fridge", R.string.fridge_title, NavigationTestTags.FRIDGE_TAB),
  RECIPES("recipes", R.string.recipes_title, NavigationTestTags.RECIPES_TAB),
  RECEIPTS("receipts", R.string.receipts_title, NavigationTestTags.RECEIPTS_TAB),
  CHAT("chat", R.string.chat_title, NavigationTestTags.CHAT_TAB),
  SETTINGS("settings", R.string.settings_title, NavigationTestTags.SETTINGS_TAB);

  /** Whether this tab should show as selected while [route] is the current screen. */
  fun isSelected(route: String?): Boolean = route == this.route || isParentOf(route)

  /** Whether [route] is a screen opened from this tab, named `<tab route>/<screen>`. */
  fun isParentOf(route: String?): Boolean = route?.startsWith("${this.route}/") == true
}

/** Screens opened from a tab. Each route starts with its tab's route, so the tab stays selected. */
object ChildRoutes {
  const val HOUSEHOLD_MEMBERS = "settings/members"

  /** The notification settings sub-page of Settings. */
  const val NOTIFICATION_SETTINGS = "settings/notifications"

  /** Key scoping the notification settings ViewModel to one user. */
  fun notificationSettingsViewModelKey(userId: String) = "notification_settings_$userId"
}
