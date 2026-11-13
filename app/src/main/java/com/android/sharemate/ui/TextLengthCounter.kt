// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.android.sharemate.R
import com.android.sharemate.model.text.codePointLength

/**
 * Supporting text of a text field showing how much of its [max] length [text] uses, counted in the
 * same Unicode code points as [com.android.sharemate.model.text.TextLimits].
 */
@Composable
fun TextLengthCounter(text: String, max: Int) {
  Text(stringResource(R.string.text_length_counter, text.codePointLength(), max))
}
