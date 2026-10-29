// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Android 13+ requires the user to grant the notification permission at runtime. */
object NotificationPermission {
  const val PERMISSION = Manifest.permission.POST_NOTIFICATIONS

  fun shouldRequest(sdkInt: Int, granted: Boolean): Boolean =
      sdkInt >= Build.VERSION_CODES.TIRAMISU && !granted

  fun shouldRequest(context: Context): Boolean =
      shouldRequest(
          Build.VERSION.SDK_INT,
          ContextCompat.checkSelfPermission(context, PERMISSION) ==
              PackageManager.PERMISSION_GRANTED)
}
