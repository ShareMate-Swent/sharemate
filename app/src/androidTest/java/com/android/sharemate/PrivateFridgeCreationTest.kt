// Co-authored-by: OpenAI Codex
package com.android.sharemate

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.HouseholdRepositoryFirestore
import com.android.sharemate.resources.C
import com.android.sharemate.ui.household.CreateHouseholdTestTags
import com.android.sharemate.ui.household.HouseholdChoiceTestTags
import com.android.sharemate.ui.navigation.NavigationTestTags
import com.android.sharemate.utils.FirebaseEmulator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateFridgeCreationTest {
  @get:Rule(order = 0)
  val firebase =
      object : ExternalResource() {
        override fun before() {
          FirebaseEmulator.clear()
          FirebaseEmulator.signInAs("fridge-demo")
        }

        override fun after() = FirebaseEmulator.clear()
      }
  @get:Rule(order = 1) val compose = createAndroidComposeRule<MainUserFlowTestActivity>()

  @Test
  fun userSelectsPrivateCreatesAndContinuesToFridge() {
    waitFor(HouseholdChoiceTestTags.CREATE)
    compose.onNodeWithTag(HouseholdChoiceTestTags.CREATE).performClick()
    compose.onNodeWithTag(CreateHouseholdTestTags.TYPE_SHARED).assertIsSelected()
    compose.onNodeWithTag(CreateHouseholdTestTags.TYPE_PRIVATE).performClick().assertIsSelected()
    compose.onNodeWithTag(CreateHouseholdTestTags.NAME).performTextInput("My private fridge")
    Espresso.closeSoftKeyboard()
    screenshot("private-fridge-selection")
    compose.onNodeWithTag(CreateHouseholdTestTags.CREATE).performClick()
    waitFor(CreateHouseholdTestTags.CONTINUE)
    compose.onNodeWithText("Your private fridge is ready!").assertIsDisplayed()
    compose.onNodeWithTag(CreateHouseholdTestTags.COPY_INVITE_CODE).assertDoesNotExist()
    compose.onNodeWithTag(CreateHouseholdTestTags.SHARE_INVITE_CODE).assertDoesNotExist()
    val household = runBlocking {
      HouseholdRepositoryFirestore(FirebaseEmulator.firestore)
          .getHouseholdForUser(checkNotNull(FirebaseEmulator.auth.uid))
    }
    assertEquals(FridgeType.PRIVATE, household?.type)
    assertEquals("", household?.inviteCode)
    assertEquals("My private fridge", household?.name)
    screenshot("private-fridge-confirmation")
    compose.onNodeWithTag(CreateHouseholdTestTags.CONTINUE).performClick()
    waitFor(NavigationTestTags.FRIDGE_TAB)
    compose
        .onNodeWithTag(C.Tag.fridge_type)
        .assertIsDisplayed()
        .assertTextEquals("Fridge type: Private")
    compose.onNodeWithTag(NavigationTestTags.SETTINGS_TAB).performClick()
    compose.onNodeWithTag(NavigationTestTags.FRIDGE_TAB).performClick()
    compose.onNodeWithTag(C.Tag.fridge_type).assertTextEquals("Fridge type: Private")
    compose.activityRule.scenario.recreate()
    waitFor(C.Tag.fridge_type)
    compose
        .onNodeWithTag(C.Tag.fridge_type)
        .assertIsDisplayed()
        .assertTextEquals("Fridge type: Private")
    screenshot("private-fridge-overview")
  }

  private fun waitFor(tag: String) {
    compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
  }

  private fun screenshot(name: String) {
    val file = File(compose.activity.getExternalFilesDir(null), "$name.png")
    val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
  }
}
