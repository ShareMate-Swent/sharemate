// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.item

import com.google.firebase.firestore.DocumentSnapshot
import org.mockito.Mockito.`when`

/** Stubs the fields every stored item document must contain. */
internal fun DocumentSnapshot.stubRequiredItemFields(
    sharing: ItemSharing = ItemSharing.PERSONAL
): DocumentSnapshot = also {
  `when`(it.get("quantity")).thenReturn(1L)
  `when`(it.get("status")).thenReturn(ItemStatus.ACTIVE.name)
  `when`(it.get("sharing")).thenReturn(sharing.name)
}
