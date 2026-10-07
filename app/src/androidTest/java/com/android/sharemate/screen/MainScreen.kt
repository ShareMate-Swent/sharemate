// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.screen

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import com.android.sharemate.resources.C
import io.github.kakaocup.compose.node.element.ComposeScreen
import io.github.kakaocup.compose.node.element.KNode

class MainScreen(semanticsProvider: SemanticsNodeInteractionsProvider) :
    ComposeScreen<MainScreen>(
        semanticsProvider = semanticsProvider,
        viewBuilderAction = { hasTestTag(C.Tag.main_screen_container) }) {

  val fridgeTitle: KNode = child { hasTestTag(C.Tag.fridge_title) }
  val emptyState: KNode = child { hasTestTag(C.Tag.fridge_empty) }
}
