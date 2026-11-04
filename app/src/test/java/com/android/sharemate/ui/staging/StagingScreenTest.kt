// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.staging

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.android.sharemate.model.staging.StagedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StagingScreenTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private val milk = StagedItem("milk", "Milk", quantity = 2, priceCents = 189)
  private val bread = StagedItem("bread", "Bread")

  private var confirmed: List<StagedItem>? = null
  private var backs = 0

  private fun setScreen(viewModel: StagingViewModel) {
    composeTestRule.setContent {
      MaterialTheme {
        StagingScreen(viewModel, onConfirm = { confirmed = it }, onBack = { backs++ })
      }
    }
  }

  private fun assertRowShows(id: String, text: String) {
    composeTestRule
        .onNode(
            hasText(text) and hasAnyAncestor(hasTestTag(StagingTestTags.item(id))),
            useUnmergedTree = true)
        .assertIsDisplayed()
  }

  @Test
  fun listsEachItemWithItsNameQuantityAndPrice() {
    setScreen(StagingViewModel(listOf(milk, bread)))

    assertRowShows("milk", "Milk")
    assertRowShows("milk", "Qty 2")
    assertRowShows("milk", "CHF 1.89")
    assertRowShows("bread", "Bread")
    assertRowShows("bread", "Qty 1")
    assertRowShows("bread", "No price")
    composeTestRule.onNodeWithTag(StagingTestTags.EMPTY).assertDoesNotExist()
  }

  @Test
  fun emptyListShowsEmptyStateAndDisablesConfirm() {
    setScreen(StagingViewModel(emptyList()))

    composeTestRule.onNodeWithTag(StagingTestTags.EMPTY).assertIsDisplayed()
    composeTestRule.onNodeWithTag(StagingTestTags.LIST).assertDoesNotExist()
    composeTestRule.onNodeWithTag(StagingTestTags.CONFIRM).assertIsNotEnabled().performClick()
    assertNull(confirmed)
  }

  @Test
  fun confirmHandsBackTheCurrentList() {
    val viewModel = StagingViewModel(listOf(milk, bread))
    setScreen(viewModel)
    viewModel.startEditing("bread")
    viewModel.updateDraftPrice("2")
    viewModel.saveDraft()

    composeTestRule.onNodeWithTag(StagingTestTags.CONFIRM).assertIsEnabled().performClick()

    assertEquals(listOf(milk, bread.copy(priceCents = 200)), confirmed)
  }

  @Test
  fun listFollowsTheViewModel() {
    val viewModel = StagingViewModel(listOf(milk, bread))
    setScreen(viewModel)

    viewModel.deleteItem("milk")
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag(StagingTestTags.item("milk")).assertDoesNotExist()
    composeTestRule.onNodeWithTag(StagingTestTags.item("bread")).assertIsDisplayed()

    viewModel.deleteItem("bread")
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag(StagingTestTags.EMPTY).assertIsDisplayed()
    composeTestRule.onNodeWithTag(StagingTestTags.CONFIRM).assertIsNotEnabled()
  }

  @Test
  fun confirmIsDisabledWhileAnItemIsBeingEdited() {
    // The edit dialog is covered by instrumented tests: text fields in dialogs never get idle
    // under Robolectric, so this test draws the stateless content with an open draft instead.
    var state by
        mutableStateOf(StagingUiState(listOf(milk), StagedItemDraft("milk", "Milk", "2", "1.89")))
    composeTestRule.setContent {
      MaterialTheme { StagingContent(state, onItemClick = {}, onConfirm = {}, onBack = {}) }
    }
    composeTestRule.onNodeWithTag(StagingTestTags.CONFIRM).assertIsNotEnabled()

    state = state.copy(draft = null)
    composeTestRule.onNodeWithTag(StagingTestTags.CONFIRM).assertIsEnabled()
  }

  @Test
  fun backArrowCallsOnBack() {
    setScreen(StagingViewModel(listOf(milk)))

    composeTestRule.onNodeWithTag(StagingTestTags.BACK).performClick()

    assertEquals(1, backs)
    assertNull(confirmed)
  }

  @Test
  fun tappingARowReportsItsId() {
    val clicked = mutableListOf<String>()
    composeTestRule.setContent {
      MaterialTheme {
        StagingContent(
            StagingUiState(listOf(milk, bread)),
            onItemClick = { clicked += it },
            onConfirm = {},
            onBack = {})
      }
    }

    composeTestRule.onNodeWithTag(StagingTestTags.item("bread")).performClick()
    composeTestRule.onNodeWithTag(StagingTestTags.item("milk")).performClick()

    assertEquals(listOf("bread", "milk"), clicked)
  }
}
