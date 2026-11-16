// Co-authored-by: Claude Sonnet 5 <noreply@anthropic.com>
package com.android.sharemate.model.push

import com.android.sharemate.model.FirestoreCollections
import com.android.sharemate.model.PushTokenFields
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/** Stubs Firestore by method name rather than with argument matchers, as sibling tests do. */
class FirebasePushTokenRepositoryTest {
  private val writes = mutableListOf<Any?>()
  private var requestedDeviceId: String? = null

  private val deviceDocument =
      mock(DocumentReference::class.java) {
        if (it.method.name == "set" && it.arguments.size == 1) {
          writes += it.arguments[0]
          Tasks.forResult<Void>(null)
        } else null
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
  fun doesNothingWhenAnyArgumentIsBlank() = runTest {
    repository.saveToken("", DEVICE, "token-123")
    repository.saveToken(USER, "", "token-123")
    repository.saveToken(USER, DEVICE, "")

    assertTrue(writes.isEmpty())
  }

  private companion object {
    const val USER = "alice"
    const val DEVICE = "device-1"
  }
}
