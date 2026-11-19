// Co-authored-by: Claude Sonnet 5 <noreply@anthropic.com>
package com.android.sharemate.model.push

import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.PushTokenFields
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.mock

/** Stubs Firestore by method name rather than with argument matchers, as sibling tests do. */
class FirebasePushTokenRepositoryTest {
  private val writes = mutableListOf<Any?>()
  private var deletes = 0
  private var requestedDeviceId: String? = null
  private var failure: Exception? = null

  private fun <T> outcome(value: T?): Task<T> =
      failure?.let { Tasks.forException(it) } ?: Tasks.forResult(value)

  private val deviceDocument =
      mock(DocumentReference::class.java) {
        when {
          it.method.name == "set" && it.arguments.size == 1 -> {
            writes += it.arguments[0]
            outcome<Void>(null)
          }
          it.method.name == "delete" -> {
            deletes++
            outcome<Void>(null)
          }
          else -> null
        }
      }

  private val pushTokens =
      mock(CollectionReference::class.java) {
        if (it.method.name == "document" && it.arguments.single() is String) {
          requestedDeviceId = it.arguments.single() as String
          deviceDocument
        } else null
      }

  private val userDocument =
      mock(DocumentReference::class.java) {
        if (it.method.name == "collection" &&
            it.arguments.single() == FirestoreCollections.PUSH_TOKENS)
            pushTokens
        else null
      }

  private val users =
      mock(CollectionReference::class.java) {
        if (it.method.name == "document" && it.arguments.single() == USER) userDocument else null
      }

  private val firestore =
      mock(FirebaseFirestore::class.java) {
        if (it.method.name == "collection" && it.arguments.single() == FirestoreCollections.USERS)
            users
        else null
      }

  private val repository = FirebasePushTokenRepository(firestore)

  private suspend fun assertRejected(action: suspend () -> Unit) {
    try {
      action()
      fail("Expected the call to be rejected")
    } catch (_: IllegalArgumentException) {
      // expected
    }
  }

  @Test
  fun savesTheTokenUnderTheGivenDeviceId() = runTest {
    repository.saveToken(USER, DEVICE, "token-123")

    assertEquals(DEVICE, requestedDeviceId)
    val saved = writes.single() as Map<*, *>
    assertEquals("token-123", saved[PushTokenFields.TOKEN])
    assertEquals(PushTokenFields.PLATFORM_ANDROID, saved[PushTokenFields.PLATFORM])
    assertTrue(saved[PushTokenFields.UPDATED_AT] is FieldValue)
  }

  @Test
  fun acceptsATokenOfExactlyTheMaximumLength() = runTest {
    repository.saveToken(USER, DEVICE, "t".repeat(PushTokenFields.MAX_TOKEN_LENGTH))

    assertEquals(1, writes.size)
  }

  @Test
  fun rejectsATokenOneCharacterOverTheMaximumLength() = runTest {
    assertRejected {
      repository.saveToken(USER, DEVICE, "t".repeat(PushTokenFields.MAX_TOKEN_LENGTH + 1))
    }

    assertTrue(writes.isEmpty())
  }

  @Test
  fun rejectsBlankArgumentsInsteadOfSilentlyIgnoringThem() = runTest {
    assertRejected { repository.saveToken("", DEVICE, "token-123") }
    assertRejected { repository.saveToken(USER, "", "token-123") }
    assertRejected { repository.saveToken(USER, DEVICE, "") }
    assertRejected { repository.saveToken(USER, DEVICE, "   ") }

    assertTrue(writes.isEmpty())
  }

  @Test
  fun aFailedWriteReachesTheCaller() = runTest {
    failure = IllegalStateException("offline")

    try {
      repository.saveToken(USER, DEVICE, "token-123")
      fail("Expected the failure to propagate")
    } catch (error: IllegalStateException) {
      assertEquals("offline", error.message)
    }
  }

  @Test
  fun deletesTheTokenOfTheGivenDevice() = runTest {
    repository.deleteToken(USER, DEVICE)

    assertEquals(DEVICE, requestedDeviceId)
    assertEquals(1, deletes)
  }

  @Test
  fun deleteRejectsBlankIdentifiers() = runTest {
    assertRejected { repository.deleteToken("", DEVICE) }
    assertRejected { repository.deleteToken(USER, "") }

    assertEquals(0, deletes)
  }

  @Test
  fun aFailedDeleteReachesTheCaller() = runTest {
    failure = IllegalStateException("denied")

    try {
      repository.deleteToken(USER, DEVICE)
      fail("Expected the failure to propagate")
    } catch (error: IllegalStateException) {
      assertEquals("denied", error.message)
    }
  }

  private companion object {
    const val USER = "alice"
    const val DEVICE = "device-1"
  }
}
