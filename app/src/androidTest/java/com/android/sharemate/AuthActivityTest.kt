package com.android.sharemate

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.ui.navigation.NavigationTestTags
import com.google.firebase.auth.FirebaseAuth
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the real application entry point never reveals content to signed-out users. */
@RunWith(AndroidJUnit4::class)
class AuthActivityTest {
  @get:Rule(order = 0) val authenticatedUser = AuthenticatedUserRule(signedIn = false)

  @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun signedOutStartupDisplaysLogin() {
    compose.runOnUiThread { FirebaseAuth.getInstance().signOut() }
    compose.waitUntil(5000) {
      compose.onAllNodes(hasTestTag("auth_submit")).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag("auth_email").assertIsDisplayed()
    compose.onNodeWithTag("auth_password").assertIsDisplayed()
    compose.onNodeWithTag("auth_submit").assertIsDisplayed()
    compose.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).assertDoesNotExist()
  }
}
