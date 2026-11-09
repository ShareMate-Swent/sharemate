// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.fridge

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.android.sharemate.model.item.ExpirationDates
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.user.ExpirationPreferences
import com.android.sharemate.model.user.ExpirationPreferencesProvider
import com.android.sharemate.model.user.FakeExpirationPreferencesRepository
import com.android.sharemate.resources.C
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The fridge cards under [ProvideSoonThreshold] use the window held by the view model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProvideSoonThresholdTest {
  @get:Rule val compose = createComposeRule()

  private val item =
      Item(
          id = "milk",
          name = "Milk",
          expirationDate = ExpirationDates.toDate(LocalDate.now().plusDays(10)),
          householdId = "household-1")

  private fun show(soonDays: Int?) {
    val repository =
        FakeExpirationPreferencesRepository(
            soonDays?.let { mapOf(USER to ExpirationPreferences(soonDays = it)) } ?: emptyMap())
    val viewModel = SoonThresholdViewModel(ExpirationPreferencesProvider(repository), USER)
    compose.setContent { ProvideSoonThreshold(viewModel) { FridgeItem(item) } }
  }

  private fun description(prefix: String) =
      "Milk, $prefix${ExpirationDates.formatCompact(item.expirationDate!!)}"

  @Test
  fun cardsAreFlaggedAgainstTheUsersWiderWindow() {
    show(soonDays = 14)

    compose
        .onNodeWithTag(C.Tag.fridge_item_prefix + item.id)
        .assertContentDescriptionEquals(description("Expires soon: Exp.: "))
  }

  @Test
  fun cardsAreNotFlaggedBeyondTheUsersNarrowerWindow() {
    show(soonDays = 7)

    compose
        .onNodeWithTag(C.Tag.fridge_item_prefix + item.id)
        .assertContentDescriptionEquals(description("Exp.: "))
  }

  @Test
  fun cardsUseTheDefaultWindowWhenNothingWasSaved() {
    show(soonDays = null)

    compose
        .onNodeWithTag(C.Tag.fridge_item_prefix + item.id)
        .assertContentDescriptionEquals(description("Exp.: "))
  }

  private companion object {
    const val USER = "alice"
  }
}
