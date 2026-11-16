// Co-authored-by: Claude Sonnet 5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceIdTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun returnsTheSameIdOnEveryCall() {
    val first = DeviceId.getOrCreate(context)
    val second = DeviceId.getOrCreate(context)

    assertNotNull(first)
    assertEquals(first, second)
  }

  @Test
  fun survivesAFreshInMemoryInstanceOfTheSameContext() {
    val created = DeviceId.getOrCreate(context)

    val readBack =
        context
            .getSharedPreferences("push_device", Context.MODE_PRIVATE)
            .getString("device_id", null)

    assertEquals(created, readBack)
  }
}
