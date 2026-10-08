package com.android.sharemate

import com.android.sharemate.model.household.Household
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import org.junit.rules.ExternalResource
import org.mockito.MockedStatic
import org.mockito.Mockito.*

/** Replaces only the Firebase SDK boundary while exercising real application components. */
class FirebaseAuthTestRule(private val startSignedIn: Boolean = false) : ExternalResource() {
  val auth: FirebaseAuth = mock(FirebaseAuth::class.java)
  private var currentUser: FirebaseUser? = null
  private var listener: FirebaseAuth.AuthStateListener? = null
  private lateinit var firebase: MockedStatic<FirebaseAuth>
  private lateinit var firestore: MockedStatic<FirebaseFirestore>

  override fun before() {
    firebase = mockStatic(FirebaseAuth::class.java)
    firebase.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
    firestore = mockStatic(FirebaseFirestore::class.java)
    val database = mock(FirebaseFirestore::class.java, RETURNS_DEEP_STUBS)
    firestore.`when`<FirebaseFirestore> { FirebaseFirestore.getInstance() }.thenReturn(database)
    val document = mock(DocumentSnapshot::class.java)
    val snapshot = mock(QuerySnapshot::class.java)
    `when`(document.toObject(Household::class.java))
        .thenReturn(Household(id = "student-home", memberIds = listOf("trusted-student-id")))
    `when`(snapshot.documents).thenReturn(listOf(document))
    `when`(
            database
                .collection("households")
                .whereArrayContains("memberIds", "trusted-student-id")
                .limit(1)
                .get())
        .thenReturn(Tasks.forResult(snapshot))
    val user = mock(FirebaseUser::class.java)
    `when`(user.uid).thenReturn("trusted-student-id")
    `when`(user.email).thenReturn("student@example.org")
    currentUser = if (startSignedIn) user else null
    `when`(auth.currentUser).thenAnswer { currentUser }
    doAnswer {
          listener = it.getArgument(0)
          listener?.onAuthStateChanged(auth)
          null
        }
        .`when`(auth)
        .addAuthStateListener(any(FirebaseAuth.AuthStateListener::class.java))
    doAnswer {
          currentUser = user
          listener?.onAuthStateChanged(auth)
          Tasks.forResult(mock(AuthResult::class.java))
        }
        .`when`(auth)
        .signInWithEmailAndPassword("student@example.org", "secret")
    doAnswer {
          currentUser = null
          listener?.onAuthStateChanged(auth)
          null
        }
        .`when`(auth)
        .signOut()
  }

  override fun after() {
    try {
      firestore.close()
    } finally {
      firebase.close()
    }
  }
}
