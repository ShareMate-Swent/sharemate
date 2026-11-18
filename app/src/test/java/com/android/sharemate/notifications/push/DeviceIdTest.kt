// Co-authored-by: Claude Sonnet 5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceIdTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val preferences =
      context.getSharedPreferences(DeviceId.PREFERENCES_NAME, Context.MODE_PRIVATE)

  @Test
  fun returnsTheSameIdOnEveryCall() {
    val first = DeviceId.getOrCreate(context)
    val second = DeviceId.getOrCreate(context)

    assertNotEquals("", first)
    assertEquals(first, second)
  }

  @Test
  fun storesTheCreatedIdInThePreferences() {
    val created = DeviceId.getOrCreate(context)

    assertEquals(created, preferences.getString(DeviceId.KEY_DEVICE_ID, null))
  }

  @Test
  fun reusesAnIdAlreadyStoredByAPreviousRun() {
    preferences.edit().putString(DeviceId.KEY_DEVICE_ID, STORED_ID).commit()

    assertEquals(STORED_ID, DeviceId.getOrCreate(context))
  }

  private companion object {
    const val STORED_ID = "stored-id"
  }
}
