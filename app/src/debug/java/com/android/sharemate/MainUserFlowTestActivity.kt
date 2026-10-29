// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate

import com.android.sharemate.model.image.FoodImageRepository
import com.android.sharemate.model.image.FoodImageSearchResult
import com.android.sharemate.model.image.FoodImageSelector

class MainUserFlowTestActivity : MainActivity() {
  // Each test controls only its activity; the default never contacts an external image service.
  var imageLookup: suspend (String) -> FoodImageSearchResult = { FoodImageSearchResult.NotFound }

  override fun createFoodImageSelector() =
      FoodImageSelector(
          object : FoodImageRepository {
            override suspend fun searchByTitle(title: String) = imageLookup(title)
          })
}
