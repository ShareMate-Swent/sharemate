// Co-authored-by: Claude Sonnet 5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

  @Test
  fun concurrentFirstCallsAllReceiveTheSamePersistedId() {
    val start = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(THREADS)
    try {
      val results =
          (1..THREADS).map {
            pool.submit<String> {
              start.await()
              DeviceId.getOrCreate(context)
            }
          }
      start.countDown()

      val ids = results.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }.toSet()

      assertEquals(1, ids.size)
      assertEquals(ids.single(), preferences.getString(DeviceId.KEY_DEVICE_ID, null))
    } finally {
      pool.shutdownNow()
    }
  }

  private companion object {
    const val THREADS = 16
    const val TIMEOUT_SECONDS = 10L
    const val STORED_ID = "stored-id"
  }
}
