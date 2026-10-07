package com.android.sharemate

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.android.sharemate.resources.C
import com.android.sharemate.ui.navigation.NavigationTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner

/** Exercises the real activity, repository, ViewModel and gate without contacting Firebase. */
@RunWith(RobolectricTestRunner::class)
class MainActivityAuthTest {
  @get:Rule(order = 0) val firebase = FirebaseAuthTestRule()
  @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun loginRevealsApplicationAndSignoutReturnsToProtectedLogin() {
    compose.onNodeWithTag("auth_submit").assertIsDisplayed()
    compose.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).assertDoesNotExist()
    compose.onNodeWithTag("auth_email").performTextInput("student@example.org")
    compose.onNodeWithTag("auth_password").performTextInput("secret")
    compose.onNodeWithTag("auth_submit").performScrollTo().performClick()
    compose.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).assertIsDisplayed().assertIsSelected()
    compose.onNodeWithTag(C.Tag.fridge_title).assertIsDisplayed().assertTextEquals("Fridge")
    compose.onNodeWithText("Your fridge is empty").assertIsDisplayed()
    compose.onNodeWithTag("auth_submit").assertDoesNotExist()
    verify(firebase.auth).signInWithEmailAndPassword("student@example.org", "secret")
    compose.onNodeWithTag(NavigationTestTags.SETTINGS_TAB).performClick()
    compose.onNodeWithText("Sign out").performClick()
    compose.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).assertDoesNotExist()
    compose.onNodeWithTag("auth_submit").assertIsDisplayed()
    verify(firebase.auth).signOut()
  }
}
