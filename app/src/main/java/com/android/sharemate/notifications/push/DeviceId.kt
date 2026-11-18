// Co-authored-by: Claude Sonnet 5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import android.content.Context
import java.util.UUID

/**
 * A random identifier for this app install, stable until the app's data is cleared or it is
 * uninstalled. It names this device's push token document, independently of who is signed in, so a
 * token can be replaced in place instead of accumulating one entry per refresh.
 */
object DeviceId {
  /** Name of the preferences file holding the identifier. */
  const val PREFERENCES_NAME = "push_device"

  /** Key of the identifier inside [PREFERENCES_NAME]. */
  const val KEY_DEVICE_ID = "device_id"

  /** Returns this install's identifier, creating and storing it on first use. */
  fun getOrCreate(context: Context): String {
    val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    return preferences.getString(KEY_DEVICE_ID, null)
        ?: UUID.randomUUID().toString().also {
          preferences.edit().putString(KEY_DEVICE_ID, it).apply()
        }
  }
}
