// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.notifications.push

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Answers
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class PushTokenRegistrarFactoryTest {
  private val auth = mock(FirebaseAuth::class.java)
  private val firestore = mock(FirebaseFirestore::class.java, Answers.RETURNS_DEEP_STUBS)
  private val messaging = mock(FirebaseMessaging::class.java)

  private fun signIn(uid: String) {
    val user = mock(FirebaseUser::class.java)
    `when`(user.uid).thenReturn(uid)
    `when`(auth.currentUser).thenReturn(user)
  }

  @Test
  fun theCurrentUserProviderReadsTheFirebaseUser() {
    val provider = FirebaseCurrentUserIdProvider(auth)
    assertNull(provider.currentUserId())

    signIn("alice")

    assertEquals("alice", provider.currentUserId())
  }

  @Test
  fun theRegistrarReadsTheFcmTokenAndWritesItForTheSignedInUser() = runTest {
    signIn("alice")
    `when`(messaging.token).thenReturn(Tasks.forResult("fcm-token"))
    val document =
        firestore.collection("users").document("alice").collection("pushTokens").document("dev-1")
    `when`(document.set(anyMap<String, Any>())).thenReturn(Tasks.forResult(null))

    val outcome =
        PushTokenRegistrarFactory.create("dev-1", auth, firestore, lazyOf(messaging)).register(null)

    assertTrue(outcome)
    verify(document).set(anyMap<String, Any>())
  }
}
