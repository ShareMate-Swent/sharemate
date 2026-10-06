package com.android.sharemate.model.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthErrorMappingTest {
  @Test
  fun loginFailuresDoNotRevealWhetherAnAccountExists() {
    for (code in
        listOf(
            "ERROR_WRONG_PASSWORD",
            "ERROR_USER_NOT_FOUND",
            "ERROR_INVALID_CREDENTIAL",
            "ERROR_INVALID_LOGIN_CREDENTIALS",
            "ERROR_USER_DISABLED",
            "ERROR_USER_TOKEN_EXPIRED",
            "ERROR_INVALID_USER_TOKEN")) {
      assertEquals(code, AuthError.INVALID_CREDENTIALS, authErrorForCode(code))
    }
  }

  @Test
  fun knownProviderFailuresHaveActionableDomainErrors() {
    val errors =
        mapOf(
            "ERROR_INVALID_EMAIL" to AuthError.INVALID_EMAIL,
            "ERROR_WEAK_PASSWORD" to AuthError.WEAK_PASSWORD,
            "ERROR_EMAIL_ALREADY_IN_USE" to AuthError.EMAIL_IN_USE,
            "ERROR_NETWORK_REQUEST_FAILED" to AuthError.NETWORK,
            "ERROR_TOO_MANY_REQUESTS" to AuthError.TOO_MANY_REQUESTS,
            "ERROR_OPERATION_NOT_ALLOWED" to AuthError.PROVIDER_DISABLED)
    errors.forEach { (code, expected) -> assertEquals(code, expected, authErrorForCode(code)) }
  }

  @Test
  fun unknownProviderCodesDoNotLeakThroughTheDomainBoundary() {
    assertEquals(AuthError.UNKNOWN, authErrorForCode("new provider code"))
  }
}
