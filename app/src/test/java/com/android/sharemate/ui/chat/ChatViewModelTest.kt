// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.chat

import com.android.sharemate.model.chat.ChatRepository
import com.android.sharemate.model.chat.Message
import com.android.sharemate.model.chat.MessageType
import com.android.sharemate.model.incident.IncidentPhotoReference
import com.android.sharemate.model.profile.ProfilePhotoPaths
import com.android.sharemate.model.profile.UserProfile
import com.android.sharemate.model.profile.UserProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
  private class FakeChatRepository : ChatRepository {
    val messages = MutableStateFlow<List<Message>>(emptyList())
    var sendResult: String? = "id"
    var failObserve = false
    val sent = mutableListOf<List<String>>()

    override fun observeMessages(householdId: String): Flow<List<Message>> =
        if (failObserve) flow { throw IllegalStateException("boom") } else messages

    override suspend fun sendActivity(
        householdId: String,
        senderId: String,
        senderName: String,
        type: MessageType,
        itemName: String,
        itemImageUrl: String?,
        activityId: String?,
    ): String? = null

    override suspend fun sendIncident(
        householdId: String,
        senderId: String,
        senderName: String,
        description: String,
        photo: IncidentPhotoReference
    ): String? = null

    override suspend fun sendMessage(
        householdId: String,
        senderId: String,
        senderName: String,
        text: String
    ): String? {
      sent += listOf(householdId, senderId, senderName, text)
      return sendResult
    }
  }

  private val repository = FakeChatRepository()

  @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

  @After fun tearDown() = Dispatchers.resetMain()

  private fun viewModel() = ChatViewModel(repository, "h1", "u1", "Alex")

  @Test
  fun exposesMessagesInRealTime() {
    val vm = viewModel()
    assertTrue(vm.state.value.isLoading.not())
    assertTrue(vm.state.value.messages.isEmpty())
    repository.messages.value = listOf(Message("1", "u2", "Sam", "hi"))
    assertEquals(listOf("hi"), vm.state.value.messages.map { it.text })
  }

  @Test
  fun sendClearsDraftOnSuccess() {
    val vm = viewModel()
    vm.onDraftChange("  hello ")
    vm.send()
    assertEquals(listOf("h1", "u1", "Alex", "  hello "), repository.sent.single())
    assertEquals("", vm.state.value.draft)
    assertFalse(vm.state.value.isSending)
    assertEquals(null, vm.state.value.error)
  }

  @Test
  fun sendFailureKeepsDraftAndReportsError() {
    repository.sendResult = null
    val vm = viewModel()
    vm.onDraftChange("hello")
    vm.send()
    assertEquals("hello", vm.state.value.draft)
    assertEquals(ChatError.SEND_FAILED, vm.state.value.error)
  }

  @Test
  fun blankDraftIsNotSent() {
    val vm = viewModel()
    vm.onDraftChange("   ")
    assertFalse(vm.state.value.canSend)
    vm.send()
    assertTrue(repository.sent.isEmpty())
  }

  @Test
  fun loadFailureIsReported() {
    repository.failObserve = true
    val vm = viewModel()
    assertEquals(ChatError.LOAD_FAILED, vm.state.value.error)
    assertFalse(vm.state.value.isLoading)
  }

  @Test
  fun draftIsCappedToMaxLength() {
    val vm = viewModel()
    vm.onDraftChange("a".repeat(Message.MAX_TEXT_LENGTH + 5))
    assertEquals(Message.MAX_TEXT_LENGTH, vm.state.value.draft.length)
  }

  @Test
  fun sendExceptionIsReported() {
    val vm =
        ChatViewModel(
            object : ChatRepository {
              override fun observeMessages(householdId: String): Flow<List<Message>> =
                  MutableStateFlow(emptyList())

              override suspend fun sendActivity(
                  householdId: String,
                  senderId: String,
                  senderName: String,
                  type: MessageType,
                  itemName: String,
                  itemImageUrl: String?,
                  activityId: String?,
              ): String? = null

              override suspend fun sendIncident(
                  householdId: String,
                  senderId: String,
                  senderName: String,
                  description: String,
                  photo: IncidentPhotoReference
              ): String? = null

              override suspend fun sendMessage(
                  householdId: String,
                  senderId: String,
                  senderName: String,
                  text: String
              ): String? = throw IllegalStateException("x")
            },
            "h1",
            "u1",
            "Alex")
    vm.onDraftChange("hi")
    vm.send()
    assertEquals(ChatError.SEND_FAILED, vm.state.value.error)
  }

  @Test
  fun newMessagesKeepSendError() {
    repository.sendResult = null
    val vm = viewModel()
    vm.onDraftChange("hello")
    vm.send()
    assertEquals(ChatError.SEND_FAILED, vm.state.value.error)
    repository.messages.value = listOf(Message("1", "u2", "Sam", "hi"))
    assertEquals(ChatError.SEND_FAILED, vm.state.value.error)
  }

  @Test
  fun cancelledSendIsNotReportedAsFailure() {
    val vm =
        ChatViewModel(
            object : ChatRepository {
              override fun observeMessages(householdId: String): Flow<List<Message>> =
                  MutableStateFlow(emptyList())

              override suspend fun sendActivity(
                  householdId: String,
                  senderId: String,
                  senderName: String,
                  type: MessageType,
                  itemName: String,
                  itemImageUrl: String?,
                  activityId: String?,
              ): String? = null

              override suspend fun sendIncident(
                  householdId: String,
                  senderId: String,
                  senderName: String,
                  description: String,
                  photo: IncidentPhotoReference
              ): String? = null

              override suspend fun sendMessage(
                  householdId: String,
                  senderId: String,
                  senderName: String,
                  text: String
              ): String? = throw kotlinx.coroutines.CancellationException("cancelled")
            },
            "h1",
            "u1",
            "Alex")
    vm.onDraftChange("hi")
    vm.send()
    assertEquals(null, vm.state.value.error)
    assertFalse(vm.state.value.isSending)
    assertTrue(vm.state.value.canSend)
  }

  private class FakeProfileRepository : UserProfileRepository {
    val members = MutableStateFlow<List<UserProfile>>(emptyList())
    var fail = false
    val observed = mutableListOf<String>()

    override suspend fun savePublicProfile(profile: UserProfile) = Unit

    override fun observeMembers(householdId: String): Flow<List<UserProfile>> {
      observed += householdId
      return if (fail) flow { throw IllegalStateException("offline") } else members
    }
  }

  private val profiles = FakeProfileRepository()

  private fun viewModelWithPhotos(bucket: String? = BUCKET) =
      ChatViewModel(repository, "h1", "u1", "Alex", profiles, bucket)

  @Test
  fun exposesSupportedMemberPhotosInRealTime() {
    val vm = viewModelWithPhotos()
    assertEquals(listOf("h1"), profiles.observed)
    assertTrue(vm.state.value.memberPhotos.isEmpty())

    profiles.members.value =
        listOf(UserProfile("u2", "Sam", GOOGLE_PHOTO), UserProfile("u3", "Lucía", null))
    assertEquals(mapOf("u2" to GOOGLE_PHOTO), vm.state.value.memberPhotos)

    profiles.members.value = listOf(UserProfile("u3", "Lucía", GOOGLE_PHOTO))
    assertEquals(mapOf("u3" to GOOGLE_PHOTO), vm.state.value.memberPhotos)
  }

  @Test
  fun dropsUnsupportedAndBlankPhotoUrls() {
    val vm = viewModelWithPhotos()
    profiles.members.value =
        listOf(
            UserProfile("u2", photoUrl = "https://evil.example.com/a.png"),
            UserProfile("u3", photoUrl = "http://lh3.googleusercontent.com/a"),
            UserProfile("u4", photoUrl = " "),
            UserProfile("u5", photoUrl = "not a url"))
    assertTrue(vm.state.value.memberPhotos.isEmpty())
  }

  @Test
  fun keepsUploadsOnlyFromTheMembersOwnFolderInTheConfiguredBucket() {
    val path = ProfilePhotoPaths.avatar("u2", "photo1").replace("/", "%2F")
    val upload = "https://firebasestorage.googleapis.com/v0/b/$BUCKET/o/$path?alt=media"
    val vm = viewModelWithPhotos()
    // u3 points at u2's photo: it is not their own upload, so it is dropped.
    profiles.members.value =
        listOf(UserProfile("u2", photoUrl = upload), UserProfile("u3", photoUrl = upload))
    assertEquals(mapOf("u2" to upload), vm.state.value.memberPhotos)

    profiles.members.value = listOf(UserProfile("u2", photoUrl = upload))
    val noBucket = viewModelWithPhotos(bucket = null)
    assertTrue(noBucket.state.value.memberPhotos.isEmpty())
  }

  @Test
  fun profileFailureLeavesPhotosOutWithoutBlockingTheChat() {
    profiles.fail = true
    val vm = viewModelWithPhotos()
    repository.messages.value = listOf(Message("1", "u2", "Sam", "hi"))

    assertTrue(vm.state.value.memberPhotos.isEmpty())
    assertEquals(null, vm.state.value.error)
    assertFalse(vm.state.value.isLoading)
    assertEquals(listOf("hi"), vm.state.value.messages.map { it.text })
  }

  @Test
  fun withoutProfileRepositoryShowsNoPhotos() {
    val vm = viewModel()
    assertTrue(vm.state.value.memberPhotos.isEmpty())
  }

  private companion object {
    const val BUCKET = "sharemate.appspot.com"
    const val GOOGLE_PHOTO = "https://lh3.googleusercontent.com/a/photo"
  }
}
