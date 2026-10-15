// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.resources.C
import com.android.sharemate.ui.household.CreateHouseholdTestTags
import com.android.sharemate.ui.household.HouseholdChoiceTestTags
import com.android.sharemate.ui.household.JoinHouseholdTestTags
import com.android.sharemate.ui.navigation.NavigationTestTags
import com.android.sharemate.ui.settings.SettingsTestTags
import com.android.sharemate.utils.FirebaseEmulator
import java.time.LocalDate
import java.util.UUID
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainUserFlowE2ETest {
  @get:Rule(order = 0)
  val firebaseEmulator =
      object : ExternalResource() {
        override fun before() {
          FirebaseEmulator.clear()
        }

        override fun after() {
          FirebaseEmulator.clear()
        }
      }

  @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun mainUserCreatesHouseholdAddsAndRemovesItemThenSignsOut() {
    val email = "main-flow-${UUID.randomUUID()}@example.org"
    val password = "secret123"
    val householdName = "Shared Home"
    val itemName = "Milk"

    compose.waitUntil(10_000) {
      compose.onAllNodesWithTag("auth_welcome").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag("auth_welcome_signup").performScrollTo().performClick()

    compose.onNodeWithTag("auth_email").performTextInput(email)
    compose.onNodeWithTag("auth_password").performTextInput(password)
    compose.onNodeWithTag("auth_confirm_password").performScrollTo().performTextInput(password)
    compose.onNodeWithTag("auth_submit").performScrollTo().performClick()

    compose.waitUntil(15_000) {
      compose.onAllNodesWithTag(HouseholdChoiceTestTags.CREATE).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(HouseholdChoiceTestTags.CREATE).performClick()
    compose.onNodeWithTag(CreateHouseholdTestTags.NAME).performTextInput(householdName)
    compose.onNodeWithTag(CreateHouseholdTestTags.CREATE).performClick()

    compose.waitUntil(15_000) {
      compose.onAllNodesWithTag(CreateHouseholdTestTags.CONTINUE).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(CreateHouseholdTestTags.CONTINUE).performClick()

    compose.waitUntil(15_000) {
      compose
          .onAllNodes(hasTestTag(NavigationTestTags.FRIDGE_TAB))
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    compose.onNodeWithTag(C.Tag.fridge_title).assertIsDisplayed().assertTextEquals("Fridge")
    compose.onNodeWithTag(C.Tag.fridge_add_item).performClick()

    compose.onNodeWithTag(C.Tag.fridge_name_input).performTextInput(itemName)
    compose.onNodeWithTag(C.Tag.fridge_quantity_input).performTextReplacement("2")
    compose.onNodeWithTag(C.Tag.fridge_category_input).performTextInput("Dairy")
    compose
        .onNodeWithTag(C.Tag.fridge_expiration_input)
        .performTextReplacement(LocalDate.now().plusDays(7).toString())
    Espresso.closeSoftKeyboard()
    compose.onNodeWithTag(C.Tag.fridge_save_item).performClick()

    compose.waitUntil(15_000) {
      compose.onAllNodesWithTag(C.Tag.fridge_add_dialog).fetchSemanticsNodes().isEmpty()
    }
    compose.onNodeWithText(itemName).assertIsDisplayed()

    compose.onNodeWithText("Remove").performClick()
    compose.onNodeWithTag(C.Tag.fridge_remove_dialog).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.fridge_confirm_remove).performClick()

    compose.waitUntil(15_000) {
      compose.onAllNodesWithText(itemName).fetchSemanticsNodes().isEmpty()
    }
    compose
        .onNodeWithTag(C.Tag.fridge_empty)
        .assertIsDisplayed()
        .assertTextEquals("Your fridge is empty")

    compose.onNodeWithTag(NavigationTestTags.SETTINGS_TAB).performClick()
    compose.onNodeWithTag(SettingsTestTags.SIGN_OUT).performClick()

    compose.waitUntil(10_000) {
      compose.onAllNodesWithTag("auth_welcome").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag("auth_welcome").assertIsDisplayed()
  }

  @Test
  fun secondUserJoinsHouseholdAndSeesFirstUsersItem() {
    val firstUserEmail = "household-owner-${UUID.randomUUID()}@example.org"
    val secondUserEmail = "household-member-${UUID.randomUUID()}@example.org"
    val password = "secret123"
    val householdName = "Shared Home"
    val itemName = "Shared Milk"

    compose.waitUntil(10_000) {
      compose.onAllNodesWithTag("auth_welcome").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag("auth_welcome_signup").performScrollTo().performClick()
    compose.onNodeWithTag("auth_email").performTextInput(firstUserEmail)
    compose.onNodeWithTag("auth_password").performTextInput(password)
    compose.onNodeWithTag("auth_confirm_password").performScrollTo().performTextInput(password)
    compose.onNodeWithTag("auth_submit").performScrollTo().performClick()

    compose.waitUntil(15_000) {
      compose.onAllNodesWithTag(HouseholdChoiceTestTags.CREATE).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(HouseholdChoiceTestTags.CREATE).performClick()
    compose.onNodeWithTag(CreateHouseholdTestTags.NAME).performTextInput(householdName)
    compose.onNodeWithTag(CreateHouseholdTestTags.CREATE).performClick()
    compose.waitUntil(15_000) {
      compose.onAllNodesWithTag(CreateHouseholdTestTags.CONTINUE).fetchSemanticsNodes().isNotEmpty()
    }

    val inviteCode =
        compose
            .onAllNodes(
                SemanticsMatcher("has visible text") {
                  it.config.contains(SemanticsProperties.Text)
                },
                useUnmergedTree = true,
            )
            .fetchSemanticsNodes()
            .asSequence()
            .flatMap { it.config[SemanticsProperties.Text].asSequence() }
            .map { it.text }
            .single { it.matches(Regex("[A-HJ-NP-Z2-9]{6}")) }

    compose.onNodeWithTag(CreateHouseholdTestTags.CONTINUE).performClick()
    compose.waitUntil(15_000) {
      compose.onAllNodesWithTag(NavigationTestTags.FRIDGE_TAB).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(C.Tag.fridge_add_item).performClick()
    compose.onNodeWithTag(C.Tag.fridge_name_input).performTextInput(itemName)
    compose.onNodeWithTag(C.Tag.fridge_quantity_input).performTextReplacement("1")
    compose.onNodeWithTag(C.Tag.fridge_category_input).performTextInput("Dairy")
    compose
        .onNodeWithTag(C.Tag.fridge_expiration_input)
        .performTextReplacement(LocalDate.now().plusDays(7).toString())
    Espresso.closeSoftKeyboard()
    compose.onNodeWithTag(C.Tag.fridge_save_item).performClick()
    compose.waitUntil(15_000) {
      compose.onAllNodesWithTag(C.Tag.fridge_add_dialog).fetchSemanticsNodes().isEmpty() &&
          compose.onAllNodesWithText(itemName).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText(itemName).assertIsDisplayed()

    compose.onNodeWithTag(NavigationTestTags.SETTINGS_TAB).performClick()
    compose.onNodeWithTag(SettingsTestTags.SIGN_OUT).performClick()
    compose.waitUntil(10_000) {
      compose.onAllNodesWithTag("auth_welcome").fetchSemanticsNodes().isNotEmpty()
    }
    compose.waitUntil(10_000) {
      compose
          .onAllNodesWithTag("auth_welcome_signup")
          .fetchSemanticsNodes()
          .singleOrNull()
          ?.config
          ?.contains(SemanticsProperties.Disabled) == false
    }

    compose.onNodeWithTag("auth_welcome_signup").performScrollTo().performClick()
    compose.onNodeWithTag("auth_email").performTextInput(secondUserEmail)
    compose.onNodeWithTag("auth_password").performTextInput(password)
    compose.onNodeWithTag("auth_confirm_password").performScrollTo().performTextInput(password)
    compose.onNodeWithTag("auth_submit").performScrollTo().performClick()
    compose.waitUntil(15_000) {
      compose.onAllNodesWithTag(HouseholdChoiceTestTags.JOIN).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(HouseholdChoiceTestTags.JOIN).performClick()
    compose.onNodeWithTag(JoinHouseholdTestTags.CODE_INPUT).performTextInput(inviteCode)
    compose.onNodeWithTag(JoinHouseholdTestTags.JOIN).performClick()

    compose.waitUntil(15_000) {
      compose.onAllNodesWithText(itemName).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText(itemName).assertIsDisplayed()
  }
}
