// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.navigation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A way to add an item, offered by the add button of the bottom bar.
 *
 * @property destination The tab whose screen runs this way of adding an item.
 * @property opensOverCurrentTab Whether it opens over the tab the user is on, instead of opening
 *   [destination] first. The fridge's form does; the receipt scan continues on Receipts.
 */
enum class AddItemMethod(
    val destination: TopLevelDestination,
    val opensOverCurrentTab: Boolean,
) {
  /** Opens the add-item form and starts the barcode photo right away. */
  BARCODE(TopLevelDestination.FRIDGE, opensOverCurrentTab = true),
  /** Opens the add-item form, to type the item in. */
  MANUAL(TopLevelDestination.FRIDGE, opensOverCurrentTab = true),
  /** Starts a receipt scan, to add everything that was bought at once. */
  RECEIPT(TopLevelDestination.RECEIPTS, opensOverCurrentTab = false),
}

/**
 * State of the add button of the bottom bar: whether its sheet is open, and the way of adding an
 * item that the user chose and that its screen has not started yet.
 *
 * The bar and the screens are separate composables, so the choice goes through this ViewModel: the
 * bar sets it, then the screen of [AddItemMethod.destination] starts it and calls [requestHandled].
 */
class AddItemMenuViewModel : ViewModel() {
  private val sheetOpen = MutableStateFlow(false)
  private val pending = MutableStateFlow<AddItemMethod?>(null)
  private var origin: TopLevelDestination? = null

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

  /**
   * Hides the sheet and asks the screen of [method] to start it.
   *
   * @param from The tab the user was on when tapping the add button, to go back to on a cancel.
   */
  fun choose(method: AddItemMethod, from: TopLevelDestination? = null) {
    sheetOpen.value = false
    pending.value = method
    origin = from
  }

  /**
   * Called when the user cancels the started way of adding an item.
   *
   * @return The tab to go back to, once: a second cancel returns null.
   */
  fun cancelled(): TopLevelDestination? = origin.also { origin = null }

  /** Called by the screen once it started [method], so it is not started again. */
  fun requestHandled(method: AddItemMethod) {
    pending.update { if (it == method) null else it }
  }
}
