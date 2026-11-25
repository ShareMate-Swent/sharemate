// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.receipt

/** Receipt cloud schema; photo layout also matches functions/receipt-deletions.js. */
internal object ReceiptCloudPaths {
  const val USERS = "users"
  const val BACKUPS = "receiptBackups"
  const val DELETIONS = "receiptDeletions"
  const val SUBMISSION = "submission"
  const val TITLE = "title"
  const val REVISION = "revision"
  const val SCANNED_AT = "scannedAtMillis"
  const val PAID_BY = "draftPaidBy"
  const val PARTICIPANTS = "draftParticipants"
  const val PHOTO_PATH = "photoPath"
  const val PHOTO_READY = "photoReady"

  fun photo(home: String, owner: String, id: String) = "receiptPhotos/$home/$owner/$id.jpg"
}
