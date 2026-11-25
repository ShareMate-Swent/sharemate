// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.navigation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A way to add an item, offered by the add button of the bottom bar. Each opens the fridge's form
 * over the tab the user is on.
 */
enum class AddItemMethod {
  /** Opens the add-item form and starts the barcode photo right away. */
  BARCODE,
  /** Opens the add-item form, to type the item in. */
  MANUAL,
}

/**
 * State of the add button of the bottom bar: whether its sheet is open, and the way of adding an
 * item that the user chose and that its screen has not started yet.
 *
 * The bar and the screens are separate composables, so the choice goes through this ViewModel: the
 * bar sets it, then the screen that runs it starts it and calls [requestHandled].
 */
class AddItemMenuViewModel : ViewModel() {
  private val sheetOpen = MutableStateFlow(false)
  private val pending = MutableStateFlow<AddItemMethod?>(null)

  /** Whether the sheet with the ways to add an item is shown. */
  val isSheetOpen: StateFlow<Boolean> = sheetOpen.asStateFlow()

  /** The way of adding an item that its screen must start, if any. */
  val request: StateFlow<AddItemMethod?> = pending.asStateFlow()

  /** Shows the sheet, from the add button. */
  fun openSheet() {
    sheetOpen.value = true
  }

  /** Hides the sheet without choosing anything: Cancel, a tap outside it or a swipe down. */
  fun dismissSheet() {
    sheetOpen.value = false
  }

  /** Hides the sheet and asks the screen that runs [method] to start it. */
  fun choose(method: AddItemMethod) {
    sheetOpen.value = false
    pending.value = method
  }

  /** Called by the screen once it started [method], so it is not started again. */
  fun requestHandled(method: AddItemMethod) {
    pending.update { if (it == method) null else it }
  }
}
