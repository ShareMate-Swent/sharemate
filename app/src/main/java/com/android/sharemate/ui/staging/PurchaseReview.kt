// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.staging

import com.android.sharemate.model.staging.StagedItem

enum class PurchaseReviewError {
  MEMBERS_UNAVAILABLE,
  MISSING_PRICE,
  TOTAL_OVERFLOW,
  TOTAL_TOO_LARGE,
  MEMBERSHIP_CHANGED,
  NONPOSITIVE_TOTAL,
  INVALID_PAYER,
  PAYER_MUST_SUBMIT,
  NO_PARTICIPANTS,
  INVALID_PARTICIPANTS,
}

data class PurchasePreview(
    val totalCents: Long?,
    val shares: Map<String, Long> = emptyMap(),
    val error: PurchaseReviewError? = null,
)

/** Immutable reviewed payload. The purchase coordinator owns persistence and household identity. */
data class ReviewedPurchase(
    val items: List<StagedItem>,
    val paidBy: String,
    val totalCents: Long,
    val shares: Map<String, Long>,
)

/**
 * Optional financial review, populated by the coordinator from current household member names.
 * Participants start empty so nobody is silently charged. Payer and participants are independent.
 * [currentUserId] is the signed-in submitter, retained independently when the payer changes.
 */
data class PurchaseReview(
    val members: Map<String, String>,
    val paidBy: String?,
    val participants: Set<String> = emptySet(),
    val membershipChanged: Boolean = false,
    val currentUserId: String? = null,
    val membersAvailable: Boolean = true,
) {
  fun withMembers(current: Map<String, String>): PurchaseReview =
      copy(
          membershipChanged =
              membershipChanged ||
                  (paidBy != null && paidBy !in current) ||
                  participants.any { it !in current },
          members = current.filterKeys { it.isNotBlank() }.toMap(),
          paidBy = paidBy?.takeIf { it in current && it.isNotBlank() },
          participants = participants.filterTo(mutableSetOf()) { it in current && it.isNotBlank() },
      )

  fun preview(items: List<StagedItem>): PurchasePreview {
    var total = 0L
    for (item in items) {
      val cents =
          item.priceCents ?: return PurchasePreview(null, error = PurchaseReviewError.MISSING_PRICE)
      // priceCents is already the row total, regardless of quantity.
      if (cents > Long.MAX_VALUE - total)
          return PurchasePreview(null, error = PurchaseReviewError.TOTAL_OVERFLOW)
      total += cents
    }
    val error =
        when {
          !membersAvailable -> PurchaseReviewError.MEMBERS_UNAVAILABLE
          membershipChanged -> PurchaseReviewError.MEMBERSHIP_CHANGED
          total > MAX_PURCHASE_CENTS -> PurchaseReviewError.TOTAL_TOO_LARGE
          total <= 0 -> PurchaseReviewError.NONPOSITIVE_TOTAL
          paidBy.isNullOrBlank() || paidBy !in members -> PurchaseReviewError.INVALID_PAYER
          currentUserId.isNullOrBlank() || currentUserId !in members ->
              PurchaseReviewError.PAYER_MUST_SUBMIT
          participants.isEmpty() -> PurchaseReviewError.NO_PARTICIPANTS
          participants.any { it.isBlank() || it !in members } ->
              PurchaseReviewError.INVALID_PARTICIPANTS
          else -> null
        }
    if (error != null) return PurchasePreview(total, error = error)
    // Stable UID order gives the first remainder members one extra cent.
    val ordered = participants.sorted()
    val base = total / ordered.size
    val remainder = total % ordered.size
    return PurchasePreview(
        total,
        ordered
            .mapIndexed { index, uid -> uid to base + if (index.toLong() < remainder) 1L else 0L }
            .toMap(),
    )
  }

  companion object {
    // Matches the deployed recordExpense contract.
    const val MAX_PURCHASE_CENTS = 100_000_000L

    fun forUser(currentUserId: String?, members: Map<String, String>): PurchaseReview =
        PurchaseReview(
            members.filterKeys { it.isNotBlank() }.toMap(),
            currentUserId?.takeIf { it.isNotBlank() && it in members },
            currentUserId = currentUserId?.takeIf { it.isNotBlank() },
        )
  }
}
