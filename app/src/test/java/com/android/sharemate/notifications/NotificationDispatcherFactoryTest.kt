// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.android.sharemate.FirebaseAuthTestRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Checks the production wiring of the notification pipeline on the Firebase test double. */
@RunWith(RobolectricTestRunner::class)
class NotificationDispatcherFactoryTest {
  @get:Rule val firebase = FirebaseAuthTestRule()

  private val context = ApplicationProvider.getApplicationContext<Application>()

  @Test
  fun dispatchesNothingAndSucceedsWhenNobodyIsSignedIn() = runTest {
    val result = NotificationDispatcherFactory.create(context).dispatch()

    assertEquals(0, result.published)
    assertTrue(result.isSuccess)
  }
}
