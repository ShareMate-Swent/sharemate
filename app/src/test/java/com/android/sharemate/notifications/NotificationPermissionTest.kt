// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionTest {
  @Test
  fun requestsOnlyOnAndroid13OrNewerWhenNotGranted() {
    assertTrue(NotificationPermission.shouldRequest(sdkInt = 33, granted = false))
    assertTrue(NotificationPermission.shouldRequest(sdkInt = 34, granted = false))
  }

  @Test
  fun doesNotRequestAlreadyGrantedPermission() {
    assertFalse(NotificationPermission.shouldRequest(sdkInt = 34, granted = true))
  }

  @Test
  fun doesNotRequestBeforeAndroid13() {
    assertFalse(NotificationPermission.shouldRequest(sdkInt = 32, granted = false))
  }
}
