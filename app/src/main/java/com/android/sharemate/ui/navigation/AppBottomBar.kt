// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.android.sharemate.R
import com.android.sharemate.ui.components.FloatingNavAction
import com.android.sharemate.ui.components.FloatingNavBar
import com.android.sharemate.ui.components.FloatingNavItem
import com.android.sharemate.ui.profile.ProfileAvatar
import com.android.sharemate.ui.theme.Dimens

/** Sizes of the profile photo that opens Settings. */
private object ProfileEntryLayout {
  /** Touch target of the entry. */
  val touchSize = 48.dp
  /** Diameter of the photo. */
  val avatarSize = 40.dp
  /** Size of the placeholder icon. */
  val iconSize = 22.dp
}

/** Test tags inside the profile photo that opens Settings. */
internal object ProfileEntryTestTags {
  /** The account photo, present when the account has one. */
  const val PHOTO = "profile_entry_photo"
  /** The default icon, shown until a photo is loaded. */
  const val PLACEHOLDER = "profile_entry_placeholder"
}

/**
 * The app's bottom bar: the main tabs around a central add button. The add button does nothing yet;
 * it will offer the ways to add an item.
 *
 * @param currentRoute The open screen, which decides the selected tab.
 * @param onSelect Called with the tab the user tapped.
 * @param modifier Applied to the bar.
 */
@Composable
internal fun AppBottomBar(
    currentRoute: String?,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
  val items =
      TopLevelDestination.bottomBar.map { destination ->
        FloatingNavItem(
            label = stringResource(destination.labelRes),
            icon = destination.icon(),
            selected = destination.isSelected(currentRoute),
            testTag = destination.testTag,
            onClick = { onSelect(destination) })
      }
  FloatingNavBar(
      items = items,
      action =
          FloatingNavAction(
              label = stringResource(R.string.nav_add_item),
              testTag = NavigationTestTags.ADD_BUTTON,
              onClick = {}),
      modifier =
          modifier
              .navigationBarsPadding()
              .padding(horizontal = Dimens.spacingM, vertical = Dimens.spacingXs))
}

/**
 * The user's profile photo, or the default icon without one. Tapping it opens Settings.
 *
 * @param photoUrl The account photo, if any.
 * @param onClick Opens Settings.
 * @param modifier Applied to the entry.
 */
@Composable
internal fun ProfileEntry(photoUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val description = stringResource(R.string.nav_open_settings)
  Box(
      modifier
          .size(ProfileEntryLayout.touchSize)
          .clip(CircleShape)
          .clickable(role = Role.Button, onClick = onClick)
          .semantics { contentDescription = description }
          .testTag(NavigationTestTags.SETTINGS_TAB),
      contentAlignment = Alignment.Center) {
        ProfileAvatar(
            photoUrl = photoUrl,
            size = ProfileEntryLayout.avatarSize,
            iconSize = ProfileEntryLayout.iconSize,
            contentDescription = null,
            photoTestTag = ProfileEntryTestTags.PHOTO,
            placeholderTestTag = ProfileEntryTestTags.PLACEHOLDER)
      }
}

/** The icon of a destination. */
private fun TopLevelDestination.icon(): ImageVector =
    when (this) {
      TopLevelDestination.FRIDGE -> Icons.Filled.Kitchen
      TopLevelDestination.RECIPES -> Icons.AutoMirrored.Filled.MenuBook
      TopLevelDestination.RECEIPTS -> Icons.AutoMirrored.Filled.ReceiptLong
      TopLevelDestination.CHAT -> Icons.AutoMirrored.Filled.Chat
      TopLevelDestination.SETTINGS -> Icons.Filled.Settings
    }
