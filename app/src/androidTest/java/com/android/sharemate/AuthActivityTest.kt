// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.ui.auth.AuthTestTags
import com.android.sharemate.ui.navigation.NavigationTestTags
import com.google.firebase.auth.FirebaseAuth
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies signed-out entry and Android back navigation on the real application. */
@RunWith(AndroidJUnit4::class)
class AuthActivityTest {
  @get:Rule(order = 0) val notificationPermission = notificationPermissionRule()

  @get:Rule(order = 0) val authenticatedUser = AuthenticatedUserRule(signedIn = false)

  @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun signedOutStartupAndBackKeepApplicationProtected() {
    compose.runOnUiThread { FirebaseAuth.getInstance().signOut() }
    compose.waitUntil(5000) {
      compose.onAllNodes(hasTestTag(AuthTestTags.WELCOME)).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).assertDoesNotExist()
    compose.onNodeWithTag(AuthTestTags.SUBMIT).assertDoesNotExist()
    compose.onNodeWithTag(AuthTestTags.WELCOME_LOGIN).performScrollTo().performClick()
    compose.onNodeWithTag(AuthTestTags.EMAIL).assertIsDisplayed()
    compose.onNodeWithTag(AuthTestTags.PASSWORD).performTextInput("private-secret")
    compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(AuthTestTags.WELCOME).assertExists()
    compose.onNodeWithTag(AuthTestTags.PASSWORD).assertDoesNotExist()
    compose.onNodeWithTag(AuthTestTags.WELCOME_LOGIN).performScrollTo().performClick()
    compose
        .onNodeWithTag(AuthTestTags.PASSWORD)
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    compose.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).assertDoesNotExist()
  }
}
