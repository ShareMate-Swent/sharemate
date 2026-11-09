// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui

/**
 * How long a ViewModel keeps its state flows running after their last collector left, so that a
 * configuration change such as a rotation does not restart them.
 */
const val STATE_STOP_TIMEOUT_MILLIS = 5_000L
