package com.android.sharemate

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.resources.C
import com.google.firebase.auth.FirebaseAuth
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies signed-out entry and Android back navigation on the real application. */
@RunWith(AndroidJUnit4::class)
class AuthActivityTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun signedOutStartupAndBackKeepApplicationProtected() {
    compose.runOnUiThread { FirebaseAuth.getInstance().signOut() }
    compose.waitUntil(5000) {
      compose.onAllNodes(hasTestTag("auth_welcome")).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(C.Tag.greeting).assertDoesNotExist()
    compose.onNodeWithTag("auth_submit").assertDoesNotExist()
    compose.onNodeWithTag("auth_welcome_login").performScrollTo().performClick()
    compose.onNodeWithTag("auth_email").assertIsDisplayed()
    compose.onNodeWithTag("auth_password").performTextInput("private-secret")
    compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag("auth_welcome").assertExists()
    compose.onNodeWithTag("auth_password").assertDoesNotExist()
    compose.onNodeWithTag("auth_welcome_login").performScrollTo().performClick()
    compose
        .onNodeWithTag("auth_password")
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    compose.onNodeWithTag(C.Tag.greeting).assertDoesNotExist()
  }
}
