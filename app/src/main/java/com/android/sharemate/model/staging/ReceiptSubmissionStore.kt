// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.staging

import com.android.sharemate.model.item.InventorySubmission

/** Keeps the purchase coordinator scoped to one retained receipt, including after recreation. */
class ReceiptSubmissionStore(
    private val delegate: InventorySubmissionStore,
    private val receiptId: String,
    private val receiptOwnerId: String? = null,
) : InventorySubmissionStore by delegate {
  override suspend fun load(ownerId: String, householdId: String): InventorySubmission? =
      delegate.loadById(receiptOwnerId ?: ownerId, householdId, receiptId)

  override suspend fun details(ownerId: String, householdId: String, id: String) =
      delegate.details(receiptOwnerId ?: ownerId, householdId, id)

  override suspend fun pendingCorrection(ownerId: String, householdId: String, id: String) =
      delegate.pendingCorrection(receiptOwnerId ?: ownerId, householdId, id)

  override suspend fun clearCorrection(ownerId: String, householdId: String, id: String) =
      delegate.clearCorrection(receiptOwnerId ?: ownerId, householdId, id)

  override suspend fun save(submission: InventorySubmission) {
    require(submission.id == receiptId)
    delegate.save(submission)
  }
}
