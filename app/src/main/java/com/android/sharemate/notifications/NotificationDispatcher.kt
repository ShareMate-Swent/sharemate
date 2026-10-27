// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.notifications

import kotlinx.coroutines.CancellationException

/** Outcome of one [NotificationDispatcher.dispatch] run. */
data class DispatchResult(val published: Int, val failedSources: Int) {
  val isSuccess: Boolean
    get() = failedSources == 0
}

/** Asks every [NotificationSource] what is due and shows it through the [NotificationPublisher]. */
class NotificationDispatcher(
    private val sources: List<NotificationSource>,
    private val publisher: NotificationPublisher,
) {
  /** A failing source never prevents the others from notifying. */
  suspend fun dispatch(): DispatchResult {
    var published = 0
    var failed = 0
    for (source in sources) {
      try {
        source.dueNotifications().forEach {
          publisher.publish(it)
          published++
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        failed++
      }
    }
    return DispatchResult(published, failed)
  }
}
