// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.android.sharemate.resources.C
import com.android.sharemate.ui.auth.AuthTestTags
import com.android.sharemate.ui.navigation.NavigationTestTags
import org.junit.Assert.assertEquals
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
  fun loginRevealsApplicationAndSignoutReturnsToWelcome() {
    compose.onNodeWithTag(AuthTestTags.WELCOME).assertIsDisplayed()
    compose.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).assertDoesNotExist()
    compose.onNodeWithTag(AuthTestTags.WELCOME_LOGIN).performScrollTo().performClick()
    compose.onNodeWithTag(AuthTestTags.EMAIL).performTextInput("student@example.org")
    compose.onNodeWithTag(AuthTestTags.PASSWORD).performTextInput("secret")
    compose.onNodeWithTag(AuthTestTags.SUBMIT).performScrollTo().performClick()
    compose.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).assertIsDisplayed().assertIsSelected()
    compose
        .onNodeWithTag(C.Tag.fridge_title)
        .assertIsDisplayed()
        .assertTextEquals(compose.activity.getString(R.string.fridge_title))
    compose.onNodeWithText(compose.activity.getString(R.string.fridge_empty)).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.fridge_add_item).assertIsEnabled()
    assertEquals(listOf("student-home"), firebase.observedHouseholds)
    compose.onNodeWithTag(AuthTestTags.SUBMIT).assertDoesNotExist()
    verify(firebase.auth).signInWithEmailAndPassword("student@example.org", "secret")
    compose.onNodeWithTag(NavigationTestTags.SETTINGS_TAB).performClick()
    compose.onNodeWithText(compose.activity.getString(R.string.auth_sign_out)).performClick()
    compose.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).assertDoesNotExist()
    compose.onNodeWithTag(AuthTestTags.WELCOME).assertIsDisplayed()
    verify(firebase.auth).signOut()
    compose.waitUntil { firebase.removedListeners == listOf("student-home") }
  }
}
