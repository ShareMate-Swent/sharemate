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
    menu.choose(AddItemMethod.BARCODE)
    assertFalse(menu.isSheetOpen.value)
    assertEquals(AddItemMethod.BARCODE, menu.request.value)

    // Another screen cannot clear a choice that is not its own.
    menu.requestHandled(AddItemMethod.MANUAL)
    assertEquals(AddItemMethod.BARCODE, menu.request.value)

    menu.requestHandled(AddItemMethod.BARCODE)
    assertNull(menu.request.value)
  }

  @Test
  fun aNewChoiceReplacesOneNotStartedYet() {
    menu.choose(AddItemMethod.MANUAL)
    menu.choose(AddItemMethod.BARCODE)
    assertEquals(AddItemMethod.BARCODE, menu.request.value)
  }
}
