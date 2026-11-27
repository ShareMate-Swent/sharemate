// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.household

/** Deletes a private household and everything in it on the server. */
interface PrivateHouseholdDeletionRepository {

  /**
   * Deletes the private household [householdId] of the signed-in user: its items and their photos,
   * its receipts, the household itself, and the link on the user's profile.
   *
   * The server deletes the household itself last, so a failed call leaves it in place. Calling
   * again with the same [householdId] only redoes what is left, and succeeds when everything is
   * already gone. Nothing is queued while offline: a failure means the server did not confirm the
   * deletion.
   *
   * @throws IllegalArgumentException if [householdId] is blank.
   * @throws Exception if the server refuses the deletion (not the creator, not a private household)
   *   or cannot be reached.
   */
  suspend fun deletePrivateHousehold(householdId: String)
}
