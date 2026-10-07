package com.android.sharemate

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.android.sharemate.ui.navigation.NavigationTestTags
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner

/** Exercises the real activity, repository, ViewModel and gate without contacting Firebase. */
@RunWith(RobolectricTestRunner::class)
class MainActivityAuthTest {
  private val auth = mock(FirebaseAuth::class.java)
  private var currentUser: FirebaseUser? = null
  private var listener: FirebaseAuth.AuthStateListener? = null
  private lateinit var firebase: MockedStatic<FirebaseAuth>

  @get:Rule(order = 0)
  val firebaseRule =
      object : ExternalResource() {
        override fun before() {
          firebase = mockStatic(FirebaseAuth::class.java)
          firebase.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
          `when`(auth.currentUser).thenAnswer { currentUser }
          val user = mock(FirebaseUser::class.java)
          `when`(user.uid).thenReturn("trusted-student-id")
          `when`(user.email).thenReturn("student@example.org")
          doAnswer {
                listener = it.getArgument(0)
                listener?.onAuthStateChanged(auth)
                null
              }
              .`when`(auth)
              .addAuthStateListener(any(FirebaseAuth.AuthStateListener::class.java))
          doAnswer {
                currentUser = user
                listener?.onAuthStateChanged(auth)
                Tasks.forResult(mock(AuthResult::class.java))
              }
              .`when`(auth)
              .signInWithEmailAndPassword("student@example.org", "secret")
          doAnswer {
                currentUser = null
                listener?.onAuthStateChanged(auth)
                null
              }
              .`when`(auth)
              .signOut()
        }

        override fun after() {
          firebase.close()
        }
      }
  @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun loginRevealsApplicationAndSignoutReturnsToProtectedLogin() {
    compose.onNodeWithTag("auth_submit").assertIsDisplayed()
    compose.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).assertDoesNotExist()
    compose.onNodeWithTag("auth_email").performTextInput("student@example.org")
    compose.onNodeWithTag("auth_password").performTextInput("secret")
    compose.onNodeWithTag("auth_submit").performScrollTo().performClick()
    compose.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).assertIsDisplayed().assertIsSelected()
    compose.onNodeWithTag(NavigationTestTags.PAGE_CONTENT).assertTextEquals("Fridge")
    compose.onNodeWithTag("auth_submit").assertDoesNotExist()
    verify(auth).signInWithEmailAndPassword("student@example.org", "secret")
    compose.onNodeWithTag(NavigationTestTags.SETTINGS_TAB).performClick()
    compose.onNodeWithText("Sign out").performClick()
    compose.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).assertDoesNotExist()
    compose.onNodeWithTag("auth_submit").assertIsDisplayed()
    verify(auth).signOut()
  }
}
