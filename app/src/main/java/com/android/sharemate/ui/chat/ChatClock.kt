// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** The local day and time zone the chat groups its messages by. */
internal data class ChatDay(val today: LocalDate, val zone: ZoneId) {
  companion object {
    /** The day of [now] in its own time zone. */
    fun of(now: ZonedDateTime): ChatDay = ChatDay(now.toLocalDate(), now.zone)
  }
}

/** System broadcasts after which the local day or time zone may have changed. */
internal val chatDayChangeActions =
    listOf(
        // Sent every minute, so midnight is never missed for long.
        Intent.ACTION_TIME_TICK,
        Intent.ACTION_DATE_CHANGED,
        Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED,
    )

/**
 * The current [ChatDay], read from [clock] and read again while the screen is shown whenever the
 * system reports a new minute, date, time or time zone. It only changes, and so only recomposes,
 * when the day or the zone does, so "Today" and "Yesterday" stay right across midnight.
 */
@Composable
internal fun rememberChatDay(clock: () -> ZonedDateTime): ChatDay {
  val context = LocalContext.current
  val currentClock by rememberUpdatedState(clock)
  val day = remember { mutableStateOf(ChatDay.of(clock())) }
  DisposableEffect(context) {
    val receiver =
        object : BroadcastReceiver() {
          override fun onReceive(context: Context, intent: Intent) {
            day.value = ChatDay.of(currentClock())
          }
        }
    val filter = IntentFilter().apply { chatDayChangeActions.forEach(::addAction) }
    // Exported because these are protected broadcasts that only the system can send; a
    // not-exported receiver would require an app permission the system does not hold before
    // Android 13.
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    // The day may have changed between the first read and the registration.
    day.value = ChatDay.of(currentClock())
    onDispose { context.unregisterReceiver(receiver) }
  }
  return day.value
}
