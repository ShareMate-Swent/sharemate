// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.receipt

import com.android.sharemate.model.item.InventorySubmission
import com.android.sharemate.model.staging.ReceiptDetails
import kotlinx.coroutines.flow.Flow

/** Complete receipt backup; drafts are private and confirmed purchases are household-visible. */
data class ReceiptCloudEntry(val submission: InventorySubmission, val details: ReceiptDetails)

/** Authenticated cloud access independent of UI and Firebase implementation types. */
interface ReceiptCloudRepository {
  /** Explicit server deletion markers; absence from a query is never proof of deletion. */
  fun observeDeleted(ownerId: String, householdId: String): Flow<Set<String>> =
      kotlinx.coroutines.flow.flowOf(emptySet())
  /** Emits the current member's drafts and the household's saved receipts. */
  fun observe(ownerId: String, householdId: String): Flow<List<ReceiptCloudEntry>>
}
