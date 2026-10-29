// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.navigation

import androidx.navigation.NavDeepLink
import androidx.navigation.navDeepLink

/**
 * The single place that defines how the app is reached from outside (e.g. from a notification).
 *
 * The same URI is used to build a notification's intent and to declare the matching [navDeepLink],
 * so the two can never drift apart. Add a function here for each new kind of target (a receipt, a
 * recipe, ...) rather than building URIs elsewhere.
 */
object AppDeepLinks {
  const val SCHEME = "sharemate"
  const val HOST = "app"

  private const val BASE = "$SCHEME://$HOST"

  /** URI opening the given top-level destination. */
  fun uri(destination: TopLevelDestination): String = "$BASE/${destination.route}"

  /** Deep links to declare on the NavHost entry of [destination]. */
  fun navDeepLinks(destination: TopLevelDestination): List<NavDeepLink> =
      listOf(navDeepLink { uriPattern = uri(destination) })
}
