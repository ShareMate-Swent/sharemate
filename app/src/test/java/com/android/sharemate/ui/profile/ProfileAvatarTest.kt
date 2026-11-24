// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.profile

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Checks that the avatar shows its placeholder with the default and with custom styling. */
@RunWith(RobolectricTestRunner::class)
class ProfileAvatarTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun placeholderShowsWithDefaultAndCustomStyling() {
    compose.setContent {
      MaterialTheme {
        ProfileAvatar(
            photoUrl = null,
            size = 40.dp,
            iconSize = 24.dp,
            contentDescription = null,
            photoTestTag = "default_photo",
            placeholderTestTag = "default_placeholder")
        ProfileAvatar(
            photoUrl = null,
            size = 40.dp,
            iconSize = 24.dp,
            contentDescription = null,
            photoTestTag = "custom_photo",
            placeholderTestTag = "custom_placeholder",
            containerColor = Color.White,
            iconTint = Color.Black,
            icon = Icons.Outlined.Person)
      }
    }
    compose.onNodeWithTag("default_placeholder").assertIsDisplayed()
    compose.onNodeWithTag("custom_placeholder").assertIsDisplayed()
    compose.onNodeWithTag("default_photo").assertDoesNotExist()
  }
}
