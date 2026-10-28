// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.notifications.expiration

import androidx.test.core.app.ApplicationProvider
import com.android.sharemate.model.item.Item
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ResourceExpirationNotificationFormatterTest {
  private val formatter =
      ResourceExpirationNotificationFormatter(
          ApplicationProvider.getApplicationContext<android.content.Context>().resources)

  private fun items(count: Int) = List(count) { Item(id = "$it", name = "item $it") }

  @Test
  fun describesExpiredItems() {
    val text = formatter.format(ExpirationSummary(expired = items(2), expiringSoon = emptyList()))

    assertEquals("Check your fridge", text.title)
    assertEquals("2 items have expired.", text.text)
  }

  @Test
  fun describesItemsExpiringSoon() {
    val text = formatter.format(ExpirationSummary(expired = emptyList(), expiringSoon = items(1)))

    assertEquals("1 item expires soon.", text.text)
  }

  @Test
  fun describesBothKinds() {
    val text = formatter.format(ExpirationSummary(expired = items(1), expiringSoon = items(3)))

    assertEquals("1 item has expired. 3 items expire soon.", text.text)
  }
}
