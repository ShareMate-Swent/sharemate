// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.ui.chat

import com.android.sharemate.model.chat.ChatRepository
import com.android.sharemate.model.chat.Message
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
}
