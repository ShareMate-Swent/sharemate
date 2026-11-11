// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.model.user

import com.google.firebase.firestore.*
import com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED
import com.google.firebase.firestore.MetadataChanges.INCLUDE
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*

/** Shared SDK callback fixture; delivery drains only the test scheduler. */
@OptIn(ExperimentalCoroutinesApi::class)
open class MemberNameObservationFixture {
  val firestore = mock(FirebaseFirestore::class.java, RETURNS_DEEP_STUBS)
  val home = firestore.collection("households").document("home")
  val directory = home.collection("memberNames")
  val householdRegistration = mock(ListenerRegistration::class.java)
  lateinit var membership: EventListener<DocumentSnapshot>
  val emissions = mutableListOf<Map<String, String>>()
  var failure: Throwable? = null
  val denied = FirebaseFirestoreException("Denied", PERMISSION_DENIED)
  val repository = FirebaseMemberNameRepository(firestore)

  init {
    `when`(home.addSnapshotListener(eq(INCLUDE), any())).thenAnswer {
      membership = it.getArgument(1)
      householdRegistration
    }
  }

  inner class Batch(val scope: TestScope, val ids: List<String>) {
    val query = mock(Query::class.java)
    val registration = mock(ListenerRegistration::class.java)
    lateinit var listener: EventListener<QuerySnapshot>

    init {
      `when`(directory.whereIn(FieldPath.documentId(), ids)).thenReturn(query)
      `when`(query.addSnapshotListener(eq(INCLUDE), any())).thenAnswer {
        listener = it.getArgument(1)
        registration
      }
    }

    fun emit(
        vararg entries: Pair<String, Any?>,
        cached: Boolean = false,
        pending: Boolean = false
    ) {
      val result = mock(QuerySnapshot::class.java)
      val documents =
          entries.map { (id, name) ->
            mock(DocumentSnapshot::class.java).also {
              `when`(it.id).thenReturn(id)
              `when`(it.get("displayName")).thenReturn(name)
            }
          }
      `when`(result.documents).thenReturn(documents)
      val snapshotMetadata = metadata(cached, pending)
      `when`(result.metadata).thenReturn(snapshotMetadata)
      listener.onEvent(result, null)
      scope.runCurrent()
    }
  }

  fun metadata(cached: Boolean, pending: Boolean) =
      mock(SnapshotMetadata::class.java).also {
        `when`(it.isFromCache).thenReturn(cached)
        `when`(it.hasPendingWrites()).thenReturn(pending)
      }

  fun TestScope.batch(vararg ids: String) = Batch(this, ids.toList())

  fun household(
      ids: Any? = listOf("owner"),
      type: String? = "SHARED",
      creator: String? = "owner",
      cached: Boolean = false,
      pending: Boolean = false,
  ): DocumentSnapshot =
      mock(DocumentSnapshot::class.java).also {
        val snapshotMetadata = metadata(cached, pending)
        `when`(it.metadata).thenReturn(snapshotMetadata)
        `when`(it.get("memberIds")).thenReturn(ids)
        `when`(it.getString("type")).thenReturn(type)
        `when`(it.getString("createdBy")).thenReturn(creator)
      }

  fun TestScope.start(): Job =
      launch {
            try {
              repository.observeNames("home", "owner").collect { emissions += it }
            } catch (error: Exception) {
              failure = error
            }
          }
          .also { runCurrent() }

  fun TestScope.emitMembership(
      snapshot: DocumentSnapshot?,
      error: FirebaseFirestoreException? = null
  ) {
    membership.onEvent(snapshot, error)
    runCurrent()
  }

  fun TestScope.stop(job: Job) {
    job.cancel()
    runCurrent()
  }
}
