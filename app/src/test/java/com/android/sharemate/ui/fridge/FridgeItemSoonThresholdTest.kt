// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.android.sharemate.model.item.ExpirationDates
import com.android.sharemate.model.item.Item
import com.android.sharemate.resources.C
import java.time.LocalDate
import java.util.Date
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The fridge card flags "expires soon" against the user's own window, not a fixed one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FridgeItemSoonThresholdTest {
  @get:Rule val compose = createComposeRule()

  private fun expiringIn(days: Long): Item =
      Item(
          id = "milk",
          name = "Milk",
          expirationDate = ExpirationDates.toDate(LocalDate.now().plusDays(days)))

  private fun show(item: Item, thresholdDays: Long?) {
    compose.setContent {
      if (thresholdDays == null) FridgeItem(item)
      else
          CompositionLocalProvider(LocalSoonThresholdDays provides thresholdDays) {
            FridgeItem(item)
          }
    }
  }

  private fun description(item: Item, prefix: String): String =
      "Milk, Personal, Owner: Unknown owner, $prefix${ExpirationDates.formatCompact(item.expirationDate as Date)}"

  @Test
  fun itemBeyondTheUsersWindowIsNotFlagged() {
    val item = expiringIn(10)
    show(item, thresholdDays = 7)

    compose
        .onNodeWithTag(C.Tag.fridge_item_prefix + item.id)
        .assertContentDescriptionEquals(description(item, "Exp.: "))
  }

  @Test
  fun itemInsideAWiderUserWindowIsFlaggedAsExpiringSoon() {
    val item = expiringIn(10)
    show(item, thresholdDays = 14)

    compose
        .onNodeWithTag(C.Tag.fridge_item_prefix + item.id)
        .assertContentDescriptionEquals(description(item, "Expires soon: Exp.: "))
  }

  @Test
  fun windowBoundaryDayIsStillExpiringSoon() {
    val item = expiringIn(7)
    show(item, thresholdDays = 7)

    compose
        .onNodeWithTag(C.Tag.fridge_item_prefix + item.id)
        .assertContentDescriptionEquals(description(item, "Expires soon: Exp.: "))
  }

  @Test
  fun withoutAUserChoiceTheDefaultWindowApplies() {
    val inside = expiringIn(ExpirationDates.SOON_THRESHOLD_DAYS)
    show(inside, thresholdDays = null)

    compose
        .onNodeWithTag(C.Tag.fridge_item_prefix + inside.id)
        .assertContentDescriptionEquals(description(inside, "Expires soon: Exp.: "))
  }

  @Test
  fun expiredItemStaysExpiredWhateverTheWindow() {
    val item = expiringIn(-1)
    show(item, thresholdDays = 60)

    compose
        .onNodeWithTag(C.Tag.fridge_item_prefix + item.id)
        .assertContentDescriptionEquals(description(item, "Expired: Exp.: "))
  }
}
