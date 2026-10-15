// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate

import com.android.sharemate.model.image.FoodImageRepository
import com.android.sharemate.model.image.FoodImageSearchResult
import com.android.sharemate.model.image.FoodImageSelector

class MainUserFlowTestActivity : MainActivity() {
  override fun createFoodImageSelector() =
      FoodImageSelector(
          object : FoodImageRepository {
            override suspend fun searchByTitle(title: String) = FoodImageSearchResult.NotFound
          })
}
