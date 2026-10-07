package com.android.sharemate

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.ui.navigation.NavigationTestTags
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest : TestCase() {

  @get:Rule(order = 0)
  val authenticatedUser =
      object : ExternalResource() {
        private val context = FirebaseApp.getInstance().applicationContext

        private lateinit var originalOptions: FirebaseOptions
        private var app: FirebaseApp? = null
        private var auth: FirebaseAuth? = null

        override fun before() {
          val original = FirebaseApp.getInstance()
          originalOptions = original.options
          original.delete()
          try {
            val options =
                FirebaseOptions.Builder()
                    .setProjectId("demo-sharemate")
                    .setApplicationId("1:123456789:android:local-navigation-test")
                    .setApiKey("demo-api-key")
                    .build()
            app = FirebaseApp.initializeApp(context, options)
            auth =
                FirebaseAuth.getInstance(requireNotNull(app)).apply {
                  useEmulator("127.0.0.1", 9099)
                }
            Tasks.await(
                requireNotNull(auth)
                    .createUserWithEmailAndPassword(
                        "navigation-${UUID.randomUUID()}@example.org", "test-secret"),
                20,
                TimeUnit.SECONDS)
          } catch (failure: Throwable) {
            restore()
            throw failure
          }
        }

        override fun after() {
          try {
            auth?.currentUser?.let { Tasks.await(it.delete(), 20, TimeUnit.SECONDS) }
          } finally {
            restore()
          }
        }

        private fun restore() {
          val signOut = runCatching { auth?.signOut() }
          val deletion = runCatching { app?.delete() }
          FirebaseApp.initializeApp(context, originalOptions)
          signOut.getOrThrow()
          deletion.getOrThrow()
        }
      }

  @get:Rule(order = 1) val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun selectingEachDestinationShowsItsPageLabel() = run {
    step("Select each bottom-bar destination") {
      val destinations =
          listOf(
              NavigationTestTags.FRIDGE_TAB to "Fridge",
              NavigationTestTags.RECIPES_TAB to "Recipes",
              NavigationTestTags.RECEIPTS_TAB to "Receipts",
              NavigationTestTags.SETTINGS_TAB to "Settings",
          )

      destinations.forEach { (testTag, label) ->
        composeTestRule.onNodeWithTag(testTag).performClick()
        composeTestRule.onNodeWithTag(testTag).assertIsSelected()
        composeTestRule.onNodeWithTag(NavigationTestTags.PAGE_CONTENT).assertTextEquals(label)
        if (label == "Recipes" || label == "Receipts") {
          composeTestRule.onNodeWithText("Coming soon").assertExists()
        } else {
          composeTestRule.onNodeWithText("Coming soon").assertDoesNotExist()
        }
      }
    }
  }
}
