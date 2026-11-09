// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.android.sharemate.R
import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.staging.StagedItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class InventorySaveFeedbackTest {
  @get:Rule val compose = createComposeRule()
  private val retained =
      InventorySubmission(
          "12345678-1234-1234-1234-123456789abc",
          "owner",
          "home",
          listOf(StagedItem("milk", "Milk", 2, 480)))

  @Test
  fun saveProgressAndFailuresRenderTheirOwnMessageWithoutPrematureSuccess() {
    val state = mutableStateOf<InventorySaveState?>(null)
    compose.setContent { MaterialTheme { InventorySaveFeedback(state.value, true) {} } }
    compose.onNodeWithTag(InventorySaveTestTags.STATUS).assertDoesNotExist()
    val messages =
        listOf(
            InventorySavePhase.LOADING to R.string.inventory_save_loading,
            InventorySavePhase.SUBMITTING to R.string.inventory_save_submitting,
            InventorySavePhase.PENDING to R.string.inventory_save_pending,
            InventorySavePhase.CONFIRMED to R.string.inventory_save_success,
            InventorySavePhase.READY to null)
    val app = RuntimeEnvironment.getApplication()
    for ((phase, message) in messages) {
      compose.runOnIdle { state.value = InventorySaveState(phase, retained) }
      if (message == null) {
        compose.onNodeWithTag(InventorySaveTestTags.STATUS).assertDoesNotExist()
      } else {
        compose
            .onNodeWithTag(InventorySaveTestTags.STATUS)
            .assertTextEquals(app.getString(message, InventorySubmission.MAX_ITEMS))
            .assertIsDisplayed()
      }
      compose.onNodeWithTag(InventorySaveTestTags.RETRY).assertDoesNotExist()
    }
  }

  @Test
  fun retryIsAvailableOnlyForRecoverableFailuresAndInvokesTheProvidedAction() {
    val state = mutableStateOf(InventorySaveState(InventorySavePhase.FAILED))
    var retries = 0
    compose.setContent { MaterialTheme { InventorySaveFeedback(state.value, true) { retries++ } } }
    val app = RuntimeEnvironment.getApplication()
    compose
        .onNodeWithTag(InventorySaveTestTags.STATUS)
        .assertTextEquals(
            app.getString(R.string.inventory_save_failed, InventorySubmission.MAX_ITEMS))
    compose.onNodeWithTag(InventorySaveTestTags.RETRY).assertDoesNotExist()
    compose.runOnIdle { state.value = state.value.copy(submission = retained, invalidInput = true) }
    compose.onNodeWithTag(InventorySaveTestTags.RETRY).assertDoesNotExist()
    compose.runOnIdle { state.value = state.value.copy(invalidInput = false) }
    compose.onNodeWithTag(InventorySaveTestTags.RETRY).assertIsDisplayed().performClick()
    assertEquals(1, retries)
    compose.runOnIdle { state.value = InventorySaveState(InventorySavePhase.RECOVERY_FAILED) }
    compose
        .onNodeWithTag(InventorySaveTestTags.STATUS)
        .assertTextEquals(
            app.getString(R.string.inventory_save_recovery_failed, InventorySubmission.MAX_ITEMS))
    compose.onNodeWithTag(InventorySaveTestTags.RETRY).assertIsDisplayed().performClick()
    assertEquals(2, retries)
  }

  @Test
  fun privateHouseholdExplainsTheRestrictionInsteadOfDisplayingInventorySuccess() {
    compose.setContent {
      MaterialTheme {
        InventorySaveFeedback(InventorySaveState(InventorySavePhase.CONFIRMED, retained), false) {
          error("Private households cannot retry inventory writes")
        }
      }
    }
    val app = RuntimeEnvironment.getApplication()
    compose
        .onNodeWithTag(InventorySaveTestTags.STATUS)
        .assertTextEquals(
            app.getString(R.string.inventory_save_shared_only, InventorySubmission.MAX_ITEMS))
        .assertIsDisplayed()
    compose.onNodeWithText(app.getString(R.string.inventory_save_success)).assertDoesNotExist()
    compose.onNodeWithTag(InventorySaveTestTags.RETRY).assertDoesNotExist()
  }
}
