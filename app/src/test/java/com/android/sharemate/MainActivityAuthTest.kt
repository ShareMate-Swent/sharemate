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
  @get:Rule(order = 0) val firebase = FirebaseAuthTestRule(pendingHouseholdLookup = true)
  @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun loginRevealsApplicationAndSignoutReturnsToWelcome() {
    compose.onNodeWithTag(AuthTestTags.WELCOME).assertIsDisplayed()
    compose.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).assertDoesNotExist()
    compose.onNodeWithTag(AuthTestTags.WELCOME_LOGIN).performScrollTo().performClick()
    compose.onNodeWithTag(AuthTestTags.EMAIL).performTextInput("student@example.org")
    compose.onNodeWithTag(AuthTestTags.PASSWORD).performTextInput("secret")
    compose.onNodeWithTag(AuthTestTags.SUBMIT).performScrollTo().performClick()
    resolveHouseholdAndAwaitFridge()
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
    compose.onNodeWithTag(NavigationTestTags.PROFILE_ENTRY).performClick()
    compose.onNodeWithText(compose.activity.getString(R.string.auth_sign_out)).performClick()
    // Signing out also purges the account's photo cache off the main thread.
    awaitDisplayed(AuthTestTags.WELCOME)
    compose.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).assertDoesNotExist()
    compose.onNodeWithTag(AuthTestTags.WELCOME).assertIsDisplayed()
    verify(firebase.pushTokenDocument()).delete()
    verify(firebase.auth).signOut()
    compose.waitUntil { firebase.removedListeners == listOf("student-home") }
  }

  @Test
  fun sessionWithoutEmailUsesDefaultActivitySenderName() {
    firebase.setSession("trusted-student-id", email = null)

    resolveHouseholdAndAwaitFridge()
    compose.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.fridge_title).assertIsDisplayed()
  }

  private fun resolveHouseholdAndAwaitFridge() {
    compose.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).assertDoesNotExist()
    compose.runOnIdle { firebase.resolveInitialHousehold() }
    // Cache activation performs IO, which Compose's idle synchronization does not await.
    awaitDisplayed(NavigationTestTags.FRIDGE_TAB)
    awaitDisplayed(C.Tag.fridge_empty)
  }

  private fun awaitDisplayed(tag: String) {
    compose.waitUntil(timeoutMillis = 5_000) { compose.onNodeWithTag(tag).isDisplayed() }
  }
}
