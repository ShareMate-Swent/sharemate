// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate

import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.household.Household
import com.android.sharemate.model.item.Item
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import org.junit.rules.ExternalResource
import org.mockito.MockedStatic
import org.mockito.Mockito.*

/** Replaces only the Firebase SDK boundary while exercising real application components. */
class FirebaseAuthTestRule(
    private val startSignedIn: Boolean = false,
    private val pendingHouseholdLookup: Boolean = false,
) : ExternalResource() {
  val auth: FirebaseAuth = mock(FirebaseAuth::class.java)
  private var currentUser: FirebaseUser? = null
  private var listener: FirebaseAuth.AuthStateListener? = null
  private lateinit var firebase: MockedStatic<FirebaseAuth>
  private lateinit var firestore: MockedStatic<FirebaseFirestore>
  private lateinit var database: FirebaseFirestore
  private val initialLookup = TaskCompletionSource<QuerySnapshot>()
  val observedHouseholds = mutableListOf<String>()
  val removedListeners = mutableListOf<String>()
  private val inventory = mutableMapOf<String, List<Item>>()
  private val callbacks = mutableMapOf<String, MutableList<EventListener<QuerySnapshot>>>()

  fun resolveInitialHousehold(
      householdId: String? = "student-home",
      type: FridgeType = FridgeType.SHARED
  ) {
    initialLookup.setResult(householdSnapshot(householdId, "trusted-student-id", type))
  }

  fun setHousehold(userId: String, householdId: String?, type: FridgeType = FridgeType.SHARED) {
    val snapshot = householdSnapshot(householdId, userId, type)
    `when`(database.collection("households").whereArrayContains("memberIds", userId).limit(1).get())
        .thenReturn(Tasks.forResult(snapshot))
  }

  fun setSession(userId: String?) {
    currentUser =
        userId?.let {
          mock(FirebaseUser::class.java).also { user ->
            `when`(user.uid).thenReturn(it)
            `when`(user.email).thenReturn("student@example.org")
          }
        }
    listener?.onAuthStateChanged(auth)
  }

  fun emitItems(householdId: String, items: List<Item>) {
    inventory[householdId] = items
    callbacks[householdId]?.toList()?.forEach { it.onEvent(itemSnapshot(items), null) }
  }

  private fun householdSnapshot(
      householdId: String?,
      userId: String,
      type: FridgeType = FridgeType.SHARED
  ): QuerySnapshot {
    val snapshot = mock(QuerySnapshot::class.java)
    val documents =
        householdId
            ?.let {
              val document = mock(DocumentSnapshot::class.java)
              `when`(document.toObject(Household::class.java))
                  .thenReturn(Household(id = it, memberIds = listOf(userId), type = type))
              listOf(document)
            }
            .orEmpty()
    `when`(snapshot.documents).thenReturn(documents)
    return snapshot
  }

  private fun itemSnapshot(items: List<Item>): QuerySnapshot {
    val snapshot = mock(QuerySnapshot::class.java)
    val documents =
        items.map { item ->
          mock(DocumentSnapshot::class.java).also { document ->
            `when`(document.id).thenReturn(item.id)
            `when`(document.getString("name")).thenReturn(item.name)
            `when`(document.getString("ownerId")).thenReturn(item.ownerId)
            `when`(document.getString("householdId")).thenReturn(item.householdId)
            `when`(document.getString("category")).thenReturn(item.category)
            `when`(document.get("quantity")).thenReturn(item.quantity.toLong())
          }
        }
    `when`(snapshot.documents).thenReturn(documents)
    return snapshot
  }

  override fun before() {
    firebase = mockStatic(FirebaseAuth::class.java)
    firebase.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
    firestore = mockStatic(FirebaseFirestore::class.java)
    database = mock(FirebaseFirestore::class.java, RETURNS_DEEP_STUBS)
    firestore.`when`<FirebaseFirestore> { FirebaseFirestore.getInstance() }.thenReturn(database)
    `when`(database.collection("items").whereEqualTo(eq("householdId"), anyString())).thenAnswer {
        invocation ->
      val householdId = invocation.getArgument<String>(1)
      val query = mock(Query::class.java)
      `when`(query.addSnapshotListener(any<EventListener<QuerySnapshot>>())).thenAnswer {
        val callback = it.getArgument<EventListener<QuerySnapshot>>(0)
        observedHouseholds += householdId
        callbacks.getOrPut(householdId) { mutableListOf() }.add(callback)
        callback.onEvent(itemSnapshot(inventory[householdId].orEmpty()), null)
        mock(ListenerRegistration::class.java).also { registration ->
          doAnswer {
                callbacks[householdId]?.remove(callback)
                removedListeners += householdId
                null
              }
              .`when`(registration)
              .remove()
        }
      }
      query
    }
    val lookupTask =
        if (pendingHouseholdLookup) initialLookup.task
        else Tasks.forResult(householdSnapshot("student-home", "trusted-student-id"))
    `when`(
            database
                .collection("households")
                .whereArrayContains("memberIds", "trusted-student-id")
                .limit(1)
                .get())
        .thenReturn(lookupTask)
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
