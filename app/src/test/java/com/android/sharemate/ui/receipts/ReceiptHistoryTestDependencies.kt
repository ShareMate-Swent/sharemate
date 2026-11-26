// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.receipts

import com.android.sharemate.model.receipt.ReceiptCloudEntry
import com.android.sharemate.model.receipt.ReceiptCloudRepository
import kotlinx.coroutines.flow.emptyFlow

/** Offline cloud source for history tests that only exercise local behavior. */
internal object EmptyReceiptCloud : ReceiptCloudRepository {
  override fun observe(ownerId: String, householdId: String) = emptyFlow<List<ReceiptCloudEntry>>()

  override fun observeDeleted(ownerId: String, householdId: String) = emptyFlow<Set<String>>()
}
