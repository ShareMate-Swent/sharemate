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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A confirmed private attachment; provisional picker images stay outside the incident draft.
 *
 * @property file Private copy owned by this ViewModel, deleted on replacement, removal or draft
 *   clearance. The host must finish reading it before clearing the draft.
 * @property importing True until the current confirmation has copied the photo or failed;
 *   attachment controls must remain disabled meanwhile.
 * @property rejected The last import failed, including unreadable images or I/O errors. Cleared by
 *   the next import attempt, [IncidentPhotoViewModel.clearRejection] or removal.
 */
data class IncidentPhotoState(
    val file: File? = null,
    val importing: Boolean = false,
    val rejected: Boolean = false,
)

/**
 * Owns the local photo for one incident draft. Scope this ViewModel to the draft's navigation
 * entry; its file survives recomposition/rotation and is deleted when that entry is cleared.
 * Uploading and message persistence belong to the incident form, which must finish reading the file
 * before ending the draft. Process-death restoration is not provided by this local attachment
 * component.
 *
 * One picker must serialize confirmations for this draft. [selectAndWait] tracks the caller's
 * confirmation job; clearing the draft intentionally cancels that job and waits for it before
 * deleting files. Prefer the same navigation-entry ViewModelStore for picker and draft. A host
 * using separate stores must still accept cancellation of the picker's pending confirmation when
 * the draft ends.
 */
class IncidentPhotoViewModel(private val files: PersonalPhotoFiles) : ViewModel() {
  private val mutableState = MutableStateFlow(IncidentPhotoState())
  /** Confirmed attachment, import progress and last failure observed by the host form. */
  val state = mutableState.asStateFlow()
  private var importing: Job? = null

  /**
   * Takes an owned copy before the shared picker releases its URI. Rejected or cancelled
   * replacements retain the confirmed photo; cancellation propagates to the picker instead of
   * becoming an error.
   */
  suspend fun selectAndWait(uri: String, fromCamera: Boolean) {
    // The single picker's busy guard serializes confirmations. A duplicate call violates that
    // contract; returning early would falsely acknowledge a photo that we never copied.
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

  /**
   * Clears an old import error when reopening the picker, preserving the attachment and any copy.
   */
  fun clearRejection() {
    mutableState.update { if (it.importing) it else it.copy(rejected = false) }
  }

  /**
   * Removes only our private copy, never the original gallery image or an in-flight replacement.
   */
  fun remove() {
    if (state.value.importing) return
    val previous = state.value.file
    mutableState.value = IncidentPhotoState()
    previous?.let { viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { release(it) } }
  }

  override fun onCleared() {
    val pending = importing
    pending?.cancel()
    // viewModelScope is already cancelled. Wait for the consumer before releasing its private file.
    CoroutineScope(Dispatchers.Main.immediate).launch {
      pending?.join()
      val file = state.value.file
      mutableState.value = IncidentPhotoState()
      file?.let { release(it) }
    }
  }

  private suspend fun release(file: File) = withContext(NonCancellable) { files.delete(file) }
}
