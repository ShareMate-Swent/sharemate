// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.finance

import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.HouseholdFields
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SnapshotMetadata

internal fun SnapshotMetadata.isServerConfirmed() = !isFromCache && !hasPendingWrites()

/** Only authoritative parent data can deny access; an absent cached parent is inconclusive. */
internal fun DocumentSnapshot.requireSharedHousehold() {
  if (metadata.isServerConfirmed() &&
      (!exists() || get(HouseholdFields.TYPE) != FridgeType.SHARED.name)) {
    throw FirebaseFirestoreException(
        "A shared household is required", FirebaseFirestoreException.Code.PERMISSION_DENIED)
  }
}
