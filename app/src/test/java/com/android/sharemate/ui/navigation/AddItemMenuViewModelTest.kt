// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the sheet of the add button and the choice it hands to the screens. */
class AddItemMenuViewModelTest {
  private val menu = AddItemMenuViewModel()

  @Test
  fun sheetStartsClosedAndOpensAndClosesWithoutAChoice() {
    assertFalse(menu.isSheetOpen.value)
    menu.openSheet()
    assertTrue(menu.isSheetOpen.value)
    menu.dismissSheet()
    assertFalse(menu.isSheetOpen.value)
    assertNull(menu.request.value)
  }

  @Test
  fun choosingClosesTheSheetAndKeepsTheChoiceUntilItsScreenHandlesIt() {
    menu.openSheet()
    menu.choose(AddItemMethod.RECEIPT)
    assertFalse(menu.isSheetOpen.value)
    assertEquals(AddItemMethod.RECEIPT, menu.request.value)

    // Another screen cannot clear a choice that is not its own.
    menu.requestHandled(AddItemMethod.MANUAL)
    assertEquals(AddItemMethod.RECEIPT, menu.request.value)

    menu.requestHandled(AddItemMethod.RECEIPT)
    assertNull(menu.request.value)
  }

  @Test
  fun cancellingGivesTheTabOfTheAddButtonOnce() {
    menu.choose(AddItemMethod.RECEIPT, from = TopLevelDestination.CHAT)
    assertEquals(TopLevelDestination.CHAT, menu.cancelled())
    assertNull(menu.cancelled())
  }

  @Test
  fun aNewChoiceReplacesOneNotStartedYet() {
    menu.choose(AddItemMethod.MANUAL)
    menu.choose(AddItemMethod.BARCODE)
    assertEquals(AddItemMethod.BARCODE, menu.request.value)
  }

  @Test
  fun barcodeAndManualRunOnTheFridgeAndReceiptOnReceipts() {
    assertEquals(TopLevelDestination.FRIDGE, AddItemMethod.BARCODE.destination)
    assertEquals(TopLevelDestination.FRIDGE, AddItemMethod.MANUAL.destination)
    assertEquals(TopLevelDestination.RECEIPTS, AddItemMethod.RECEIPT.destination)
    assertTrue(AddItemMethod.BARCODE.opensOverCurrentTab)
    assertTrue(AddItemMethod.MANUAL.opensOverCurrentTab)
    assertFalse(AddItemMethod.RECEIPT.opensOverCurrentTab)
  }
}
