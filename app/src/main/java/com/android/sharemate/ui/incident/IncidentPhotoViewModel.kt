// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.incident

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sharemate.model.image.PersonalPhotoFiles
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A local attachment only: no image bytes, upload or incident persistence. */
data class IncidentPhotoState(
    val file: File? = null,
    val importing: Boolean = false,
    val rejected: Boolean = false,
)

/** Owns the confirmed attachment for one incident draft; the picker owns provisional images. */
class IncidentPhotoViewModel(private val files: PersonalPhotoFiles) : ViewModel() {
  private val mutableState = MutableStateFlow(IncidentPhotoState())
  val state = mutableState.asStateFlow()
  private var importing: Job? = null

  /** Copies a picker URI before it is released. A failed/cancelled replacement keeps the draft. */
  suspend fun selectAndWait(uri: String, fromCamera: Boolean) {
    check(!state.value.importing)
    viewModelScope.coroutineContext.ensureActive()
    val operation = currentCoroutineContext().job
    importing = operation
    val previous = state.value.file
    mutableState.value = IncidentPhotoState(previous, importing = true)
    var candidate: File? = null
    try {
      candidate = files.copy(uri, fromCamera)
      currentCoroutineContext().ensureActive()
      viewModelScope.coroutineContext.ensureActive()
      mutableState.value = IncidentPhotoState(candidate)
      candidate = null
      previous?.let { release(it) }
    } catch (cancelled: CancellationException) {
      mutableState.value = IncidentPhotoState(previous)
      throw cancelled
    } catch (failure: Exception) {
      mutableState.value = IncidentPhotoState(previous, rejected = true)
      throw failure
    } finally {
      candidate?.let { release(it) }
      if (importing === operation) importing = null
    }
  }

  /** Removes only the private attachment copy, never the original gallery image. */
  fun remove() {
    if (state.value.importing) return
    val previous = state.value.file
    mutableState.value = IncidentPhotoState()
    previous?.let { viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { release(it) } }
  }

  override fun onCleared() {
    val pending = importing
    pending?.cancel()
    // The ViewModel scope is already cancelled. Await the bounded file operation before cleanup.
    CoroutineScope(Dispatchers.Main.immediate).launch {
      pending?.join()
      val file = state.value.file
      mutableState.value = IncidentPhotoState()
      file?.let { release(it) }
    }
  }

  private suspend fun release(file: File) = withContext(NonCancellable) { files.delete(file) }
}
