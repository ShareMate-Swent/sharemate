// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.staging

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.android.sharemate.R
import com.android.sharemate.model.staging.Prices

/** Display-only controls; membership comes from the purchase coordinator, never Firebase here. */
@Composable
internal fun PurchaseReviewContent(
    preview: PurchasePreview?,
    review: PurchaseReview,
    onPayer: (String) -> Unit,
    onParticipant: (String) -> Unit,
    enabled: Boolean,
) {
  Column(Modifier.fillMaxWidth().testTag("staging_purchase_review")) {
    Text(
        stringResource(
            R.string.purchase_total,
            preview?.totalCents?.let(Prices::format) ?: stringResource(R.string.staging_no_price),
        ))
    Text(stringResource(R.string.purchase_paid_by), style = MaterialTheme.typography.titleSmall)
    review.members.toSortedMap().forEach { (uid, name) ->
      Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(
            selected = review.paidBy == uid,
            onClick = { onPayer(uid) },
            enabled = enabled,
            modifier = Modifier.testTag("staging_payer_$uid"),
        )
        Text(name.ifBlank { uid })
      }
    }
    Text(
        stringResource(R.string.purchase_participants),
        style = MaterialTheme.typography.titleSmall,
    )
    Text(stringResource(R.string.purchase_rounding), style = MaterialTheme.typography.bodySmall)
    review.members.toSortedMap().forEach { (uid, name) ->
      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = uid in review.participants,
            onCheckedChange = { onParticipant(uid) },
            enabled = enabled,
            modifier = Modifier.testTag("staging_participant_$uid"),
        )
        Text(name.ifBlank { uid }, modifier = Modifier.weight(1f))
        preview?.shares?.get(uid)?.let {
          Text(
              stringResource(R.string.staging_price, Prices.format(it)),
              modifier = Modifier.testTag("staging_share_$uid"),
          )
        }
      }
    }
    preview?.error?.let { error ->
      val message =
          when (error) {
            PurchaseReviewError.MISSING_PRICE -> R.string.purchase_missing_price
            PurchaseReviewError.TOTAL_TOO_LARGE -> R.string.purchase_overflow
            PurchaseReviewError.MEMBERSHIP_CHANGED -> R.string.purchase_membership_changed
            PurchaseReviewError.TOTAL_OVERFLOW -> R.string.purchase_overflow
            PurchaseReviewError.NONPOSITIVE_TOTAL -> R.string.purchase_positive_total
            PurchaseReviewError.INVALID_PAYER -> R.string.purchase_invalid_payer
            PurchaseReviewError.NO_PARTICIPANTS -> R.string.purchase_no_participants
            PurchaseReviewError.INVALID_PARTICIPANTS -> R.string.purchase_invalid_participants
          }
      Text(
          stringResource(message),
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag("staging_purchase_error"),
      )
    }
  }
}
