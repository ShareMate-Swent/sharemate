// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.fridge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Gives the fridge cards inside [content] the user's "expiring soon" window held by [viewModel].
 * Without it, the cards use the default window; every screen showing fridge cards must be wrapped
 * in it.
 */
@Composable
fun ProvideSoonThreshold(viewModel: SoonThresholdViewModel, content: @Composable () -> Unit) {
  val soonThresholdDays by viewModel.soonThresholdDays.collectAsStateWithLifecycle()
  CompositionLocalProvider(LocalSoonThresholdDays provides soonThresholdDays, content = content)
}
