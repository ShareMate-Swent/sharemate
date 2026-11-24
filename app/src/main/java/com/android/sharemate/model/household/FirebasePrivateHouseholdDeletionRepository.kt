// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.household

import com.android.sharemate.model.CloudFunctions
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

/**
 * Deletes a private household through the authenticated `deletePrivateHousehold` callable, which
 * checks on the server that the caller created it and that it is private.
 *
 * @param functionsProvider Creates the Functions client. It runs on the first deletion, not when
 *   the screen that offers it opens, so that screen never depends on the Functions SDK.
 */
class FirebasePrivateHouseholdDeletionRepository(functionsProvider: () -> FirebaseFunctions) :
    PrivateHouseholdDeletionRepository {
  constructor(functions: FirebaseFunctions) : this({ functions })

  private val functions by lazy(functionsProvider)

  override suspend fun deletePrivateHousehold(householdId: String) {
    require(householdId.isNotBlank()) { "Household ID must not be blank" }
    val payload = mapOf(HouseholdFields.HOUSEHOLD_ID to householdId)
    val response =
        functions
            .getHttpsCallable(HouseholdFunctions.DELETE_PRIVATE_HOUSEHOLD)
            .call(payload)
            .await()
            .data
    check(response == payload) { "Unexpected household deletion response" }
  }

  companion object {
    /** Region hosting the household callables. */
    const val REGION = CloudFunctions.REGION
  }
}
