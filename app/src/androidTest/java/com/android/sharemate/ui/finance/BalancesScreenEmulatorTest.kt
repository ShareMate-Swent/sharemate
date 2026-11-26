// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.finance

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.sharemate.model.finance.*
import com.android.sharemate.ui.theme.ShareMateTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real-device rendering with deterministic injected financial state; no Firebase writes. */
@RunWith(AndroidJUnit4::class)
class BalancesScreenEmulatorTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun rendersDebtsAndClearsThemOnErrorWithRetry() {
    val summary =
        BalanceCalculator.calculate(
            HouseholdFinanceSnapshot(
                "home",
                listOf(SharedExpense("e", "home", "bob", 1500, mapOf("alice" to 1500L))),
                emptyList()),
            listOf("alice", "bob"))
    var state by
        mutableStateOf<BalancesUiState>(
            BalancesUiState.Ready("alice", summary, mapOf("bob" to "Bob")))
    var retries = 0
    var backs = 0
    var settled: Debt? = null
    var darkTheme by mutableStateOf(false)
    compose.setContent {
      ShareMateTheme(darkTheme = darkTheme) {
        BalancesScreen(state, { retries++ }, onSettle = { settled = it }, onBack = { backs++ })
      }
    }
    compose.onNodeWithText("You owe Bob", substring = true).assertIsDisplayed()
    compose.onNodeWithTag(BalancesTestTags.BACK).performClick()
    assertEquals(1, backs)
    compose.onNodeWithText("Settle up", ignoreCase = true).performClick()
    assertEquals(summary.debts.single(), settled)
    screenshot("balances-ready")
    compose.runOnIdle { darkTheme = true }
    compose.onNodeWithTag(BalancesTestTags.SUMMARY).assertIsDisplayed()
    screenshot("balances-ready-dark")
    compose.runOnIdle { darkTheme = false }
    compose.runOnIdle { state = BalancesUiState.Error }
    compose.onNodeWithTag(BalancesTestTags.SUMMARY).assertDoesNotExist()
    compose.onNodeWithTag(BalancesTestTags.RETRY).performClick()
    assertEquals(1, retries)
    screenshot("balances-error")
  }

  @Test
  fun receiptsEntryIsHiddenForPrivateFridgesAndShownForSharedFridges() {
    val store =
        object : com.android.sharemate.model.image.TemporaryPhotoStore {
          override suspend fun createCapture(): String = error("unused")

          override suspend fun importImage(uri: String): String = error("unused")

          override suspend fun validate(uri: String) = Unit

          override suspend fun delete(uri: String) = Unit
        }
    val photo =
        com.android.sharemate.ui.photo.PhotoPickerViewModel(
            store, androidx.lifecycle.SavedStateHandle())
    var shared by mutableStateOf(false)
    var opens = 0
    compose.setContent {
      ShareMateTheme {
        com.android.sharemate.ui.receipts.ReceiptScanFlow(photo, shared, { opens++ }) {
          emptyList()
        }
      }
    }
    compose.onNodeWithTag(BalancesTestTags.ENTRY).assertDoesNotExist()
    screenshot("balances-receipts-private")
    compose.runOnIdle { shared = true }
    compose.onNodeWithTag(BalancesTestTags.ENTRY).performClick()
    assertEquals(1, opens)
    screenshot("balances-receipts-shared")
    compose.runOnIdle { shared = false }
    compose.onNodeWithTag(BalancesTestTags.ENTRY).assertDoesNotExist()
  }

  private fun screenshot(name: String) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val destination = File(context.getExternalFilesDir(null), "$name.png")
    val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
    destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
