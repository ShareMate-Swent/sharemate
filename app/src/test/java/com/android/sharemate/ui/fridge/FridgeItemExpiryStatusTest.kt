// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.fridge

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.android.sharemate.model.item.ExpirationDates
import com.android.sharemate.model.item.ExpiryStatus
import com.android.sharemate.model.item.Item
import com.android.sharemate.resources.C
import java.time.LocalDate
import java.util.Date
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The fridge card shows the status it is given and works nothing out about the dates itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FridgeItemExpiryStatusTest {
  @get:Rule val compose = createComposeRule()

  private val item =
      Item(
          id = "milk",
          name = "Milk",
          expirationDate = ExpirationDates.toDate(LocalDate.now().plusDays(10)),
          householdId = "household-1")

  private fun show(status: ExpiryStatus) {
    compose.setContent { FridgeItem(item, expiryStatus = status) }
  }

  private fun description(prefix: String): String =
      "Milk, $prefix${ExpirationDates.formatCompact(item.expirationDate as Date)}"

  @Test
  fun expiredStatusIsFlaggedAsExpired() {
    show(ExpiryStatus.EXPIRED)

    compose
        .onNodeWithTag(C.Tag.fridge_item_prefix + item.id)
        .assertContentDescriptionEquals(description("Expired: Exp.: "))
  }

  @Test
  fun soonStatusIsFlaggedAsExpiringSoon() {
    show(ExpiryStatus.SOON)

    compose
        .onNodeWithTag(C.Tag.fridge_item_prefix + item.id)
        .assertContentDescriptionEquals(description("Expires soon: Exp.: "))
  }

  @Test
  fun normalStatusIsNotFlagged() {
    show(ExpiryStatus.NORMAL)

    compose
        .onNodeWithTag(C.Tag.fridge_item_prefix + item.id)
        .assertContentDescriptionEquals(description("Exp.: "))
  }
}
