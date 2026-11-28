// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A column that fills the screen and scrolls when its content is taller, for example on a small
 * phone or with a large system font, so the last action can always be reached.
 *
 * It must be placed in a parent with a bounded height, such as a screen or a `Box` of a fixed size.
 * Inside another vertical scroller, `verticalScroll` would be measured with an infinite height and
 * fail.
 *
 * @param verticalArrangement How the children are placed vertically, such as with a fixed gap.
 * @param content The column's content.
 */
@Composable
fun ScrollingColumn(
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    Column(
        modifier =
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
        verticalArrangement = verticalArrangement,
        content = content,
    )
  }
}
