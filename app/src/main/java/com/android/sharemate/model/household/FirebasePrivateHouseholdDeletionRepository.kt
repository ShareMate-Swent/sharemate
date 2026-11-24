// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.household

import com.android.sharemate.model.CloudFunctions
import com.google.firebase.functions.FirebaseFunctions
import java.util.concurrent.TimeUnit
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
            .withTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .call(payload)
            .await()
            .data
    check(response == payload) { "Unexpected household deletion response" }
  }

  companion object {
    /** Region hosting the household callables. */
    const val REGION = CloudFunctions.REGION

    /**
     * How long the app waits for the server. The SDK gives up after 70 seconds by default, but the
     * function may run for 300 seconds (`DELETE_HOUSEHOLD_TIMEOUT_SECONDS` in
     * `functions/index.js`). Waiting less would offer "Try again" while the first deletion is still
     * running. Keep this above the function's timeout.
     */
    const val CALL_TIMEOUT_SECONDS = 330L
  }
}
