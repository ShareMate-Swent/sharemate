// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.staging

import com.android.sharemate.model.staging.StagedItem
import com.android.sharemate.model.text.TextLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StagingViewModelTest {
  private val milk = StagedItem("milk", "Milk", quantity = 2, priceCents = 189)
  private val bread = StagedItem("bread", "Bread")

  private fun viewModel(vararg items: StagedItem) = StagingViewModel(items.toList())

  @Test
  fun showsTheInitialItems() {
    val state = viewModel(milk, bread).state.value

    assertEquals(listOf(milk, bread), state.items)
    assertNull(state.draft)
    assertTrue(state.canConfirm)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsDuplicateIds() {
    viewModel(milk, milk.copy(name = "Other milk"))
  }

  @Test
  fun startEditingFillsTheDraftWithCurrentValues() {
    val viewModel = viewModel(milk, bread)

    viewModel.startEditing("milk")
    assertEquals(StagedItemDraft("milk", "Milk", "2", "1.89"), viewModel.state.value.draft)

    viewModel.startEditing("bread")
    assertEquals(StagedItemDraft("bread", "Bread", "1", ""), viewModel.state.value.draft)
  }

  @Test
  fun startEditingUnknownItemDoesNothing() {
    val viewModel = viewModel(milk)

    viewModel.startEditing("missing")

    assertNull(viewModel.state.value.draft)
  }

  @Test
  fun savingAValidDraftUpdatesOnlyThatItem() {
    val viewModel = viewModel(milk, bread)
    viewModel.startEditing("milk")
    viewModel.updateDraftName("  Oat milk ")
    viewModel.updateDraftQuantity(" 3 ")
    viewModel.updateDraftPrice("2,40")

    viewModel.saveDraft()

    val state = viewModel.state.value
    assertEquals(listOf(StagedItem("milk", "Oat milk", 3, 240), bread), state.items)
    assertNull(state.draft)
  }

  @Test
  fun clearingThePriceRemovesIt() {
    val viewModel = viewModel(milk)
    viewModel.startEditing("milk")
    viewModel.updateDraftPrice("")

    viewModel.saveDraft()

    assertNull(viewModel.state.value.items.single().priceCents)
  }

  @Test
  fun addingAnItemWithTheSameNameAppendsAnIndependentRow() {
    val viewModel = viewModel(milk)
    viewModel.startAdding()
    viewModel.updateDraftName(" Milk ")
    viewModel.updateDraftQuantity("3")
    viewModel.updateDraftPrice("2,40")
    assertNull(viewModel.confirmedItems())

    viewModel.saveDraft()

    val (original, added) = requireNotNull(viewModel.confirmedItems())
    assertEquals(milk, original)
    assertTrue(added.id != original.id)
    assertEquals(StagedItem(added.id, "Milk", 3, 240), added)

    viewModel.startEditing(added.id)
    viewModel.updateDraftQuantity("4")
    viewModel.saveDraft()
    assertEquals(listOf(milk, added.copy(quantity = 4)), viewModel.confirmedItems())
    viewModel.deleteItem(added.id)
    assertEquals(listOf(milk), viewModel.confirmedItems())
  }

  @Test
  fun addingWithInvalidValuesLeavesTheListUnchangedUntilCorrected() {
    val viewModel = viewModel(milk)
    viewModel.startAdding()
    viewModel.saveDraft()
    assertEquals(StagedItemError.NAME_REQUIRED, viewModel.state.value.draft?.error)
    assertEquals(listOf(milk), viewModel.state.value.items)

    viewModel.updateDraftName("Bread")
    viewModel.updateDraftQuantity("0")
    viewModel.saveDraft()
    assertEquals(StagedItemError.INVALID_QUANTITY, viewModel.state.value.draft?.error)
    assertEquals(listOf(milk), viewModel.state.value.items)

    viewModel.updateDraftQuantity("2")
    viewModel.updateDraftPrice("-1")
    viewModel.saveDraft()
    assertEquals(StagedItemError.INVALID_PRICE, viewModel.state.value.draft?.error)
    assertEquals(listOf(milk), viewModel.state.value.items)
    assertNull(viewModel.confirmedItems())

    viewModel.updateDraftPrice("")
    viewModel.saveDraft()
    val added = requireNotNull(viewModel.confirmedItems()).last()
    assertEquals(StagedItem(added.id, "Bread", 2), added)
  }

  @Test
  fun cancellingAnAdditionDoesNotChangeAnEmptyListAndItCanBeRepopulated() {
    val viewModel = viewModel()
    viewModel.startAdding()
    viewModel.updateDraftName("Discard me")
    viewModel.updateDraftQuantity("3")
    viewModel.updateDraftPrice("1.25")
    viewModel.cancelEditing()
    assertNull(viewModel.state.value.draft)
    assertNull(viewModel.confirmedItems())

    viewModel.startAdding()
    viewModel.updateDraftName("Bread")
    viewModel.saveDraft()

    val added = requireNotNull(viewModel.confirmedItems()).single()
    assertEquals(StagedItem(added.id, "Bread", 1), added)
  }

  @Test
  fun invalidValuesKeepTheDraftOpenWithAnError() {
    val cases =
        listOf(
            Triple(" ", "1", "") to StagedItemError.NAME_REQUIRED,
            Triple("Milk", "0", "") to StagedItemError.INVALID_QUANTITY,
            Triple("Milk", "-2", "") to StagedItemError.INVALID_QUANTITY,
            Triple("Milk", "two", "") to StagedItemError.INVALID_QUANTITY,
            Triple("Milk", "1", "1.234") to StagedItemError.INVALID_PRICE,
            Triple("Milk", "1", "-1") to StagedItemError.INVALID_PRICE,
        )
    for ((values, error) in cases) {
      val viewModel = viewModel(milk)
      viewModel.startEditing("milk")
      viewModel.updateDraftName(values.first)
      viewModel.updateDraftQuantity(values.second)
      viewModel.updateDraftPrice(values.third)

      viewModel.saveDraft()

      val state = viewModel.state.value
      assertEquals(values.toString(), error, state.draft?.error)
      assertEquals(listOf(milk), state.items)
    }
  }

  @Test
  fun nameErrorIsReportedBeforeOtherErrors() {
    val viewModel = viewModel(milk)
    viewModel.startEditing("milk")
    viewModel.updateDraftName("")
    viewModel.updateDraftQuantity("0")
    viewModel.updateDraftPrice("x")

    viewModel.saveDraft()

    assertEquals(StagedItemError.NAME_REQUIRED, viewModel.state.value.draft?.error)
  }

  @Test
  fun typingClearsTheError() {
    val viewModel = viewModel(milk)
    viewModel.startEditing("milk")
    viewModel.updateDraftPrice("x")
    viewModel.saveDraft()

    viewModel.updateDraftPrice("1.00")
    assertNull(viewModel.state.value.draft?.error)

    viewModel.updateDraftQuantity("0")
    viewModel.saveDraft()
    viewModel.updateDraftQuantity("1")
    assertNull(viewModel.state.value.draft?.error)

    viewModel.updateDraftName("")
    viewModel.saveDraft()
    viewModel.updateDraftName("Milk")
    assertNull(viewModel.state.value.draft?.error)
  }

  @Test
  fun cancelEditingLeavesTheItemUnchanged() {
    val viewModel = viewModel(milk)
    viewModel.startEditing("milk")
    viewModel.updateDraftName("Juice")

    viewModel.cancelEditing()

    assertEquals(listOf(milk), viewModel.state.value.items)
    assertNull(viewModel.state.value.draft)
  }

  @Test
  fun draftActionsWithoutAnOpenDraftDoNothing() {
    val viewModel = viewModel(milk)
    val before = viewModel.state.value

    viewModel.updateDraftName("Juice")
    viewModel.updateDraftQuantity("5")
    viewModel.updateDraftPrice("1")
    viewModel.saveDraft()

    assertEquals(before, viewModel.state.value)
  }

  @Test
  fun deletingRemovesOnlyThatItem() {
    val viewModel = viewModel(milk, bread)

    viewModel.deleteItem("milk")

    assertEquals(listOf(bread), viewModel.state.value.items)
  }

  @Test
  fun deletingUnknownItemDoesNothing() {
    val viewModel = viewModel(milk)

    viewModel.deleteItem("missing")

    assertEquals(listOf(milk), viewModel.state.value.items)
  }

  @Test
  fun deletingTheEditedItemClosesItsDraft() {
    val viewModel = viewModel(milk, bread)
    viewModel.startEditing("milk")

    viewModel.deleteItem("milk")

    assertNull(viewModel.state.value.draft)
  }

  @Test
  fun deletingAnotherItemKeepsTheDraft() {
    val viewModel = viewModel(milk, bread)
    viewModel.startEditing("milk")

    viewModel.deleteItem("bread")

    assertEquals("milk", viewModel.state.value.draft?.itemId)
  }

  @Test
  fun confirmReturnsTheEditedList() {
    val viewModel = viewModel(milk, bread)
    viewModel.startEditing("bread")
    viewModel.updateDraftPrice("2")
    viewModel.saveDraft()
    viewModel.deleteItem("milk")

    assertEquals(listOf(StagedItem("bread", "Bread", 1, 200)), viewModel.confirmedItems())
  }

  @Test
  fun cannotConfirmAnEmptyList() {
    val viewModel = viewModel(milk)
    viewModel.deleteItem("milk")

    assertFalse(viewModel.state.value.canConfirm)
    assertNull(viewModel.confirmedItems())
    assertNull(viewModel(*emptyArray()).confirmedItems())
  }

  @Test
  fun cannotConfirmWhileEditing() {
    val viewModel = viewModel(milk)
    viewModel.startEditing("milk")

    assertFalse(viewModel.state.value.canConfirm)
    assertNull(viewModel.confirmedItems())

    viewModel.cancelEditing()
    assertEquals(listOf(milk), viewModel.confirmedItems())
  }

  @Test
  fun pastedNameIsCutToTheItemNameLimit() {
    val viewModel = viewModel(milk)
    viewModel.startEditing("milk")

    viewModel.updateDraftName("a".repeat(TextLimits.ITEM_NAME * 10))
    assertEquals("a".repeat(TextLimits.ITEM_NAME), viewModel.state.value.draft?.name)
    viewModel.saveDraft()

    assertEquals("a".repeat(TextLimits.ITEM_NAME), viewModel.confirmedItems()?.single()?.name)
  }

  @Test
  fun nameAtTheLimitIsKeptAndEmojiAreNotSplit() {
    val viewModel = viewModel(milk)
    viewModel.startAdding()

    viewModel.updateDraftName("🍎".repeat(TextLimits.ITEM_NAME))
    assertEquals("🍎".repeat(TextLimits.ITEM_NAME), viewModel.state.value.draft?.name)
    viewModel.updateDraftName("🍎".repeat(TextLimits.ITEM_NAME + 1))
    assertEquals("🍎".repeat(TextLimits.ITEM_NAME), viewModel.state.value.draft?.name)
  }

  @Test
  fun updatingTheNameWithoutAnOpenDraftDoesNothing() {
    val viewModel = viewModel(milk)

    viewModel.updateDraftName("a".repeat(TextLimits.ITEM_NAME + 1))

    assertNull(viewModel.state.value.draft)
  }
}
