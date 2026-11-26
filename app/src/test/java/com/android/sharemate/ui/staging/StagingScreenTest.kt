// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.staging

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.android.sharemate.R
import com.android.sharemate.model.staging.StagedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StagingScreenTest {
  @Test
  fun receiptDeletionRequiresConfirmationAndCancelDoesNothing() {
    var deleted = 0
    composeTestRule.setContent {
      MaterialTheme {
        StagingContent(StagingUiState(), {}, {}, {}, {}, onDeleteReceipt = { deleted++ })
      }
    }
    composeTestRule.onNodeWithTag(StagingTestTags.RECEIPT_MORE).performClick()
    composeTestRule.onNodeWithTag(StagingTestTags.RECEIPT_DELETE).performClick()
    composeTestRule
        .onNode(hasText("Are you sure you want to delete this receipt?"))
        .assertIsDisplayed()
    composeTestRule.onNode(hasText("Cancel")).performClick()
    assertEquals(0, deleted)
    composeTestRule.onNodeWithTag(StagingTestTags.RECEIPT_MORE).performClick()
    composeTestRule.onNodeWithTag(StagingTestTags.RECEIPT_DELETE).performClick()
    composeTestRule.onNode(hasText("Delete", substring = false)).performClick()
    assertEquals(1, deleted)
  }

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private val milk = StagedItem("milk", "Milk", quantity = 2, priceCents = 189)
  private val bread = StagedItem("bread", "Bread")

  private var confirmed: List<StagedItem>? = null
  private var backs = 0

  @Test
  fun onlyItemsScrollWhilePreviewAndActionsRemainFixed() {
    composeTestRule.setContent {
      MaterialTheme {
        StagingContent(
            state =
                StagingUiState(
                    items = (0 until 50).map { milk.copy(id = "milk-$it") },
                    purchase = PurchaseReview.forUser("a", mapOf("a" to "Alex"))),
            onItemClick = {},
            onDeleteItem = {},
            onConfirm = {},
            onBack = {},
            onAddItem = {},
            continueToSharing = true,
            previewContent = {
              Text("Receipt title", Modifier.testTag("fixed-title"))
              Box(Modifier.fillMaxWidth().height(60.dp).testTag("fixed-photo"))
            })
      }
    }
    val fixed =
        listOf(
            "fixed-title",
            "fixed-photo",
            StagingTestTags.ADD,
            StagingTestTags.TOTAL,
            StagingTestTags.CONFIRM)
    val before =
        fixed.map {
          composeTestRule.onNodeWithTag(it).assertIsDisplayed().getUnclippedBoundsInRoot()
        }
    composeTestRule.onNodeWithTag(StagingTestTags.LIST).performScrollToIndex(40)
    composeTestRule.onNodeWithTag(StagingTestTags.item("milk-40")).assertIsDisplayed()
    val after =
        fixed.map {
          composeTestRule.onNodeWithTag(it).assertIsDisplayed().getUnclippedBoundsInRoot()
        }
    assertEquals(before, after)
  }

  @Test
  fun totalStaysCloseToContinueWithShortAndLongItemLists() {
    val rows = mutableStateOf(listOf(milk))
    composeTestRule.setContent {
      MaterialTheme {
        StagingContent(
            StagingUiState(
                items = rows.value, purchase = PurchaseReview.forUser("a", mapOf("a" to "Alex"))),
            {},
            {},
            {},
            {},
            continueToSharing = true)
      }
    }
    fun totalBottom(): androidx.compose.ui.unit.Dp {
      val total =
          composeTestRule
              .onNodeWithTag(StagingTestTags.TOTAL)
              .assertIsDisplayed()
              .getUnclippedBoundsInRoot()
      val button =
          composeTestRule
              .onNodeWithTag(StagingTestTags.CONFIRM)
              .assertIsDisplayed()
              .getUnclippedBoundsInRoot()
      assertTrue(button.top - total.bottom in 0.dp..16.dp)
      return total.bottom
    }
    val initialBottom = totalBottom()
    composeTestRule.runOnIdle { rows.value = (0 until 50).map { milk.copy(id = "milk-$it") } }
    assertEquals(initialBottom, totalBottom())
  }

  @Test
  fun compactReviewButtonsOpenCorrectDraftAndDeleteOnlyTheirItem() {
    val vm =
        StagingViewModel(listOf(milk, bread), PurchaseReview.forUser("a", mapOf("a" to "Alex")))
    composeTestRule.setContent {
      MaterialTheme {
        StagingContent(
            state = vm.state.collectAsState().value,
            onItemClick = vm::startEditing,
            onDeleteItem = vm::deleteItem,
            onConfirm = {},
            onBack = {},
            onAddItem = vm::startAdding,
            continueToSharing = true)
      }
    }
    composeTestRule
        .onNode(
            hasText(
                composeTestRule.activity.getString(R.string.staging_quantity, milk.quantity)) and
                hasAnyAncestor(hasTestTag(StagingTestTags.item("milk"))),
            useUnmergedTree = true)
        .assertIsDisplayed()
    composeTestRule.onNodeWithTag(StagingTestTags.item("milk")).performClick()
    composeTestRule.runOnIdle {
      assertEquals("milk", vm.state.value.draft?.itemId)
      vm.cancelEditing()
    }
    composeTestRule.onNodeWithTag(StagingTestTags.delete("milk")).performClick()
    composeTestRule.runOnIdle {
      assertEquals(listOf(bread), vm.state.value.items)
      assertNull(vm.state.value.draft)
    }
    composeTestRule.onNodeWithTag(StagingTestTags.ADD).performClick()
    composeTestRule.runOnIdle {
      assertNull(vm.state.value.draft?.itemId)
      assertEquals("", vm.state.value.draft?.name)
    }
  }

  @Test
  fun savedReceiptCanShowItsStoredSharesWithoutAnotherSave() {
    val vm =
        StagingViewModel(
            listOf(milk),
            PurchaseReview.forUser("a", mapOf("a" to "Alex", "b" to "Blair"))
                .copy(paidBy = "b", participants = setOf("a")))
    composeTestRule.setContent {
      MaterialTheme {
        StagingScreen(
            vm,
            onConfirm = {},
            onBack = {},
            actionsEnabled = false,
            separateSharingStep = true,
            savedPurchase = true,
            savedPreview = PurchasePreview(189, mapOf("a" to 189L)))
      }
    }
    composeTestRule.onNodeWithTag(StagingTestTags.ADD).assertDoesNotExist()
    composeTestRule.onNodeWithTag(StagingTestTags.CONFIRM).performClick()
    composeTestRule.onNodeWithTag("staging_share_a").assertIsDisplayed()
    composeTestRule.onNodeWithTag("staging_payer_b").assertIsNotEnabled()
    composeTestRule.onNodeWithTag(StagingTestTags.CONFIRM_PURCHASE).assertDoesNotExist()
  }

  @Test
  fun bothPurchaseLayoutsShareConfirmationGuards() {
    val separate = mutableStateOf(false)
    val enabled = mutableStateOf(true)
    val hasCallback = mutableStateOf(true)
    val vm = StagingViewModel(listOf(milk), PurchaseReview.forUser("a", mapOf("a" to "Alex")))
    var submissions = 0
    composeTestRule.setContent {
      MaterialTheme {
        StagingScreen(
            vm,
            {},
            {},
            actionsEnabled = enabled.value,
            onConfirmPurchase =
                if (hasCallback.value) {
                  { submissions++ }
                } else null,
            separateSharingStep = separate.value)
      }
    }
    for (separated in listOf(false, true)) {
      composeTestRule.runOnIdle { separate.value = separated }
      if (separated) composeTestRule.onNodeWithTag(StagingTestTags.CONFIRM).performClick()
      val confirm = composeTestRule.onNodeWithTag(StagingTestTags.CONFIRM_PURCHASE)
      confirm.performScrollTo().assertIsNotEnabled()
      composeTestRule.runOnIdle { vm.toggleParticipant("a") }
      confirm.assertIsEnabled().performClick()
      composeTestRule.runOnIdle { enabled.value = false }
      confirm.assertIsNotEnabled()
      composeTestRule.runOnIdle {
        enabled.value = true
        hasCallback.value = false
      }
      confirm.assertIsNotEnabled()
      composeTestRule.runOnIdle {
        hasCallback.value = true
        vm.toggleParticipant("a")
      }
    }
    assertEquals(2, submissions)
  }

  @Test
  fun separateSharingStepDoesNotSubmitOnContinueAndReturnsToItems() {
    val vm = StagingViewModel(listOf(milk), PurchaseReview.forUser("a", mapOf("a" to "Alex")))
    var purchase: ReviewedPurchase? = null
    composeTestRule.setContent {
      MaterialTheme {
        StagingScreen(
            vm,
            onConfirm = { confirmed = it },
            onBack = { backs++ },
            onConfirmPurchase = { purchase = it },
            separateSharingStep = true)
      }
    }
    composeTestRule.onNodeWithTag(StagingTestTags.CONFIRM).performClick()
    composeTestRule.onNodeWithTag(StagingTestTags.CONFIRM_PURCHASE).assertIsNotEnabled()
    assertNull(confirmed)
    assertNull(purchase)
    composeTestRule.onNodeWithTag(StagingTestTags.BACK).performClick()
    composeTestRule.onNodeWithTag(StagingTestTags.ADD).assertIsDisplayed()
    assertEquals(0, backs)
  }

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
  fun purchaseModeLabelsPinnedButtonAndSavesOnlyInventory() {
    val review = PurchaseReview.forUser("a", mapOf("a" to "Alex", "b" to "Blair"))
    val vm = StagingViewModel(listOf(milk), review)
    vm.selectPayer("b")
    vm.toggleParticipant("a")
    var purchase: ReviewedPurchase? = null
    composeTestRule.setContent {
      MaterialTheme {
        StagingScreen(
            vm, onConfirm = { confirmed = it }, onBack = {}, onConfirmPurchase = { purchase = it })
      }
    }
    val context = ApplicationProvider.getApplicationContext<Context>()
    composeTestRule
        .onNodeWithTag(StagingTestTags.CONFIRM)
        .assertTextEquals(context.getString(R.string.purchase_save_inventory_only))
        .assertIsEnabled()
        .performClick()
    assertEquals(listOf(milk), confirmed)
    assertNull(purchase)
  }

  @Test
  fun inventoryModeKeepsConfirmLabel() {
    setScreen(StagingViewModel(listOf(milk)))
    val context = ApplicationProvider.getApplicationContext<Context>()
    composeTestRule
        .onNodeWithTag(StagingTestTags.CONFIRM)
        .assertTextEquals(context.getString(R.string.staging_confirm))
  }

  @Test
  fun anotherHouseholdMemberCanPayIndependentlyOfParticipants() {
    val vm =
        StagingViewModel(
            listOf(milk), PurchaseReview.forUser("a", mapOf("a" to "Alex", "b" to "Blair")))
    vm.toggleParticipant("b")
    var purchase: ReviewedPurchase? = null
    composeTestRule.setContent {
      MaterialTheme {
        StagingScreen(vm, onConfirm = {}, onBack = {}, onConfirmPurchase = { purchase = it })
      }
    }
    composeTestRule.onNodeWithTag("staging_payer_b").performScrollTo().performClick()
    composeTestRule
        .onNodeWithTag(StagingTestTags.CONFIRM_PURCHASE)
        .performScrollTo()
        .assertIsEnabled()
        .performClick()
    assertEquals("b", purchase?.paidBy)
    composeTestRule.onNodeWithTag("staging_payer_a").performScrollTo().performClick()
    composeTestRule
        .onNodeWithTag(StagingTestTags.CONFIRM_PURCHASE)
        .performScrollTo()
        .assertIsEnabled()
        .performClick()
    assertEquals("a", purchase?.paidBy)
  }

  @Test
  fun listsEachItemWithItsNameQuantityAndPrice() {
    setScreen(StagingViewModel(listOf(milk, bread)))

    assertRowShows("milk", "Milk")
    assertRowShows("milk", "Qty 2")
    assertRowShows("milk", "1.89 CHF")
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
  fun missingScannedPriceShowsErrorAndBlocksContinue() {
    setScreen(StagingViewModel(listOf(milk, bread)))

    composeTestRule.onNodeWithTag(StagingTestTags.CONFIRM).performClick()

    composeTestRule.onNodeWithTag(StagingTestTags.MISSING_PRICES).assertIsDisplayed()
    assertNull(confirmed)
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
      MaterialTheme {
        StagingContent(state, onItemClick = {}, onDeleteItem = {}, onConfirm = {}, onBack = {})
      }
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
            onDeleteItem = {},
            onConfirm = {},
            onBack = {})
      }
    }

    composeTestRule.onNodeWithTag(StagingTestTags.item("bread")).performClick()
    composeTestRule.onNodeWithTag(StagingTestTags.item("milk")).performClick()

    assertEquals(listOf("bread", "milk"), clicked)
  }

  @Test
  fun deleteButtonRemovesOnlyThatItemWithoutEditingIt() {
    val viewModel = StagingViewModel(listOf(milk, bread))
    setScreen(viewModel)

    composeTestRule.onNodeWithTag(StagingTestTags.delete("milk")).performClick()

    composeTestRule.onNodeWithTag(StagingTestTags.item("milk")).assertDoesNotExist()
    assertRowShows("bread", "Bread")
    assertEquals(listOf(bread), viewModel.state.value.items)
    assertNull(viewModel.state.value.draft)
  }

  @Test
  fun swipingARowToTheLeftDeletesIt() {
    val viewModel = StagingViewModel(listOf(milk, bread))
    setScreen(viewModel)

    composeTestRule.onNodeWithTag(StagingTestTags.item("bread")).performTouchInput { swipeLeft() }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag(StagingTestTags.item("bread")).assertDoesNotExist()
    assertEquals(listOf(milk), viewModel.state.value.items)
  }

  @Test
  fun swipingARowToTheRightKeepsIt() {
    val viewModel = StagingViewModel(listOf(milk))
    setScreen(viewModel)

    composeTestRule.onNodeWithTag(StagingTestTags.item("milk")).performTouchInput { swipeRight() }
    composeTestRule.waitForIdle()

    assertRowShows("milk", "Milk")
    assertEquals(listOf(milk), viewModel.state.value.items)
  }

  @Test
  fun deletingTheLastItemShowsEmptyStateAndDisablesConfirm() {
    setScreen(StagingViewModel(listOf(milk)))

    composeTestRule.onNodeWithTag(StagingTestTags.delete("milk")).performClick()

    composeTestRule.onNodeWithTag(StagingTestTags.EMPTY).assertIsDisplayed()
    composeTestRule.onNodeWithTag(StagingTestTags.CONFIRM).assertIsNotEnabled()
  }
}
