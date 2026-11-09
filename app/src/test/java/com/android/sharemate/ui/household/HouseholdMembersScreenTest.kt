// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.household

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.android.sharemate.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HouseholdMembersScreenTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private var retries = 0
  private var backs = 0

  private val me =
      HouseholdMember(
          uid = "me",
          displayName = "Inge",
          email = "inge@example.com",
          photoUrl = "https://example.org/inge.png",
          isCurrentUser = true)
  private val emailOnly = HouseholdMember(uid = "sam", email = "sam@example.com")
  private val unknown = HouseholdMember(uid = "newcomer")

  private fun show(state: HouseholdMembersUiState) {
    composeTestRule.setContent {
      MaterialTheme {
        HouseholdMembersScreen(state = state, onRetry = { retries++ }, onBack = { backs++ })
      }
    }
  }

  private fun onMemberPart(tag: String) = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)

  @Test
  fun eachMemberShowsTheirNameOrTheirEmailAndOnlyTheCurrentUserIsYou() {
    show(HouseholdMembersUiState(members = listOf(me, emailOnly, unknown), isLoading = false))

    onMemberPart(HouseholdMembersTestTags.name("me")).assertTextEquals("Inge")
    onMemberPart(HouseholdMembersTestTags.you("me")).assertTextEquals("You")
    onMemberPart(HouseholdMembersTestTags.name("sam")).assertTextEquals("sam@example.com")
    onMemberPart(HouseholdMembersTestTags.you("sam")).assertDoesNotExist()
    onMemberPart(HouseholdMembersTestTags.name("newcomer"))
        .assertTextEquals(composeTestRule.activity.getString(R.string.household_member_unknown))
    // A name hides the email, so members only see it when there is nothing else to show.
    composeTestRule
        .onNodeWithTag(HouseholdMembersTestTags.member("me"))
        .assertTextEquals("Inge", "You")
  }

  @Test
  fun membersWithoutAPhotoGetAPlaceholderAvatar() {
    show(HouseholdMembersUiState(members = listOf(me, emailOnly), isLoading = false))

    onMemberPart(HouseholdMembersTestTags.photo("me")).assertExists()
    onMemberPart(HouseholdMembersTestTags.photo("sam")).assertDoesNotExist()
    onMemberPart(HouseholdMembersTestTags.placeholder("sam"))
        .assert(hasAnyAncestor(hasTestTag(HouseholdMembersTestTags.member("sam"))))
  }

  @Test
  fun loadingShowsOnlyTheProgressIndicator() {
    show(HouseholdMembersUiState())

    composeTestRule.onNodeWithTag(HouseholdMembersTestTags.LOADING).assertIsDisplayed()
    composeTestRule.onNodeWithTag(HouseholdMembersTestTags.LIST).assertDoesNotExist()
    composeTestRule.onNodeWithTag(HouseholdMembersTestTags.ERROR).assertDoesNotExist()
  }

  @Test
  fun anErrorReplacesTheListAndRetryAsksToLoadAgain() {
    show(HouseholdMembersUiState(members = listOf(me), isLoading = false, hasError = true))

    composeTestRule
        .onNodeWithTag(HouseholdMembersTestTags.ERROR)
        .assertTextEquals(composeTestRule.activity.getString(R.string.household_members_error))
    composeTestRule.onNodeWithTag(HouseholdMembersTestTags.LIST).assertDoesNotExist()

    composeTestRule.onNodeWithTag(HouseholdMembersTestTags.RETRY).performClick()

    assertEquals(1, retries)
  }

  @Test
  fun backReturnsToSettings() {
    show(HouseholdMembersUiState(members = listOf(me), isLoading = false))

    composeTestRule.onNodeWithTag(HouseholdMembersTestTags.BACK).performClick()

    assertEquals(1, backs)
    assertEquals(0, retries)
  }
}
