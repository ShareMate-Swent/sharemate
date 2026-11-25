// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.settings

import androidx.lifecycle.ViewModelStore
import com.android.sharemate.model.auth.AuthError
import com.android.sharemate.model.auth.AuthException
import com.android.sharemate.model.auth.AuthRepository
import com.android.sharemate.model.auth.AuthSession
import com.android.sharemate.model.auth.SignInProvider
import com.android.sharemate.model.image.PersonalPhotoFiles
import com.android.sharemate.model.profile.ProfilePhotoRepository
import com.android.sharemate.model.user.MAX_PSEUDO_LENGTH
import com.android.sharemate.model.user.PseudoRepository
import com.android.sharemate.ui.profile.ProfilePhotoError
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditProfileViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val session =
      AuthSession(
          uid = "user-1",
          email = "user@example.com",
          displayName = "Inge",
          photoUrl = "https://example.org/photo.png",
          provider = SignInProvider.GOOGLE)
  private lateinit var auth: FakeAuthRepository
  private lateinit var pseudos: RecordingPseudoRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    auth = FakeAuthRepository(session)
    pseudos = RecordingPseudoRepository("Inge")
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private val events = mutableListOf<String>()
  private val photos = FakePhotoRepository()
  private val files = FakeFiles()

  private fun createViewModel() = EditProfileViewModel(auth, pseudos, photos, files)

  @Test
  fun showsThePhotoOfTheSessionAndTheSavedPseudoOnceLoaded() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()

    assertEquals(
        EditProfileUiState(
            photoUrl = "https://example.org/photo.png", pseudo = "Inge", savedPseudo = "Inge"),
        viewModel.state.value)
  }

  @Test
  fun followsThePhotoOfTheSession() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()

    auth.sessionState.value = session.copy(photoUrl = "https://example.org/new.png")
    advanceUntilIdle()

    assertEquals("https://example.org/new.png", viewModel.state.value.photoUrl)
  }

  @Test
  fun readsTheNewUsersPseudoAfterTheSessionChangesAndIgnoresTheFirstUsersLoad() = runTest {
    val firstUserLoad = CompletableDeferred<Unit>()
    pseudos.pseudosByUser["user-1"] = "First user's pseudo"
    pseudos.pseudosByUser["user-2"] = "Second user's pseudo"
    pseudos.getGates["user-1"] = firstUserLoad
    val viewModel = createViewModel()
    advanceUntilIdle()

    assertEquals(listOf("user-1"), pseudos.requestedUserIds)

    auth.sessionState.value =
        AuthSession("user-2", "new@example.com", provider = SignInProvider.GOOGLE)
    advanceUntilIdle()

    assertEquals(listOf("user-1"), pseudos.cancelledUserIds)
    assertEquals("Second user's pseudo", viewModel.state.value.pseudo)
    assertFalse(viewModel.state.value.isLoadingPseudo)

    firstUserLoad.complete(Unit)
    advanceUntilIdle()

    assertEquals("Second user's pseudo", viewModel.state.value.pseudo)
    assertEquals("Second user's pseudo", viewModel.state.value.savedPseudo)
  }

  @Test
  fun savesTheTrimmedPseudoAndKeepsTheSavedValueInState() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()

    viewModel.updatePseudo("  New pseudo  ")
    viewModel.savePseudo()
    advanceUntilIdle()

    assertEquals("New pseudo", pseudos.stored)
    assertEquals("New pseudo", viewModel.state.value.pseudo)
    assertEquals("New pseudo", viewModel.state.value.savedPseudo)
    assertNull(viewModel.state.value.pseudoError)
  }

  @Test
  fun reportsPseudoLoadAndSaveFailuresAndAllowsRetry() = runTest {
    pseudos.getFailure = IllegalStateException("offline")
    val viewModel = createViewModel()
    advanceUntilIdle()

    assertEquals(EditProfilePseudoError.LOAD_FAILED, viewModel.state.value.pseudoError)
    assertFalse(viewModel.state.value.isLoadingPseudo)

    viewModel.updatePseudo("Blocked")
    viewModel.savePseudo()
    advanceUntilIdle()
    assertNotEquals("Blocked", pseudos.stored)

    pseudos.getFailure = null
    pseudos.stored = "Existing"
    viewModel.retryLoadPseudo()
    advanceUntilIdle()
    assertEquals("Existing", viewModel.state.value.pseudo)
    assertNull(viewModel.state.value.pseudoError)

    pseudos.setFailure = IllegalStateException("offline")
    viewModel.updatePseudo("New pseudo")
    viewModel.savePseudo()
    advanceUntilIdle()
    assertEquals(EditProfilePseudoError.SAVE_FAILED, viewModel.state.value.pseudoError)
    assertFalse(viewModel.state.value.isSavingPseudo)

    pseudos.setFailure = null
    viewModel.savePseudo()
    advanceUntilIdle()
    assertEquals("New pseudo", viewModel.state.value.savedPseudo)
    assertNull(viewModel.state.value.pseudoError)
  }

  @Test
  fun takenPseudoShowsSpecificErrorAndEditingClearsIt() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()
    pseudos.setFailure = AuthException(AuthError.PSEUDO_TAKEN)
    viewModel.updatePseudo("Taken")
    viewModel.savePseudo()
    advanceUntilIdle()

    assertEquals(EditProfilePseudoError.PSEUDO_TAKEN, viewModel.state.value.pseudoError)
    assertEquals("Inge", viewModel.state.value.savedPseudo)
    assertFalse(viewModel.state.value.isSavingPseudo)

    viewModel.updatePseudo("Available")
    assertNull(viewModel.state.value.pseudoError)
  }

  @Test
  fun capsTheEditedPseudoAtTheMaximumLength() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()
    viewModel.updatePseudo("a".repeat(MAX_PSEUDO_LENGTH + 5))
    viewModel.savePseudo()
    advanceUntilIdle()

    assertEquals("a".repeat(MAX_PSEUDO_LENGTH), viewModel.state.value.savedPseudo)
    assertEquals("a".repeat(MAX_PSEUDO_LENGTH), pseudos.stored)
  }

  @Test
  fun capsTheEditedPseudoAtTheMaximumNumberOfUnicodeCodePoints() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()
    val expected = "😀".repeat(MAX_PSEUDO_LENGTH)

    viewModel.updatePseudo("😀".repeat(MAX_PSEUDO_LENGTH + 5))
    viewModel.savePseudo()
    advanceUntilIdle()

    assertEquals(expected, viewModel.state.value.pseudo)
    assertEquals(expected, viewModel.state.value.savedPseudo)
    assertEquals(expected, pseudos.stored)
  }

  @Test
  fun doesNotSaveAPseudoLongerThanTheMaximumAfterTrimming() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()
    viewModel.updatePseudo("a".repeat(MAX_PSEUDO_LENGTH) + " ")
    viewModel.savePseudo()
    advanceUntilIdle()

    assertEquals("a".repeat(MAX_PSEUDO_LENGTH), pseudos.stored)
  }

  @Test
  fun cancellationDuringASaveIsNotReportedAsAFailure() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()
    pseudos.setFailure = CancellationException("cancelled")
    viewModel.updatePseudo("New pseudo")
    viewModel.savePseudo()
    advanceUntilIdle()

    assertNull(viewModel.state.value.pseudoError)
    assertFalse(viewModel.state.value.isSavingPseudo)
  }

  @Test
  fun cancellationDuringALoadDoesNotLeaveTheLoadingIndicatorActive() = runTest {
    pseudos.getFailure = CancellationException("cancelled")
    val viewModel = createViewModel()
    advanceUntilIdle()

    assertFalse(viewModel.state.value.isLoadingPseudo)
    assertNull(viewModel.state.value.pseudoError)
  }

  @Test
  fun doesNotSaveBlankOrUnchangedPseudo() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()

    viewModel.savePseudo()
    viewModel.updatePseudo("   ")
    viewModel.savePseudo()
    advanceUntilIdle()

    assertEquals(0, pseudos.saveCount)
  }

  @Test
  fun doesNotSavePseudoMadeOnlyOfInvisiblePadding() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()

    viewModel.updatePseudo("\u200B")
    assertFalse(viewModel.state.value.canSavePseudo)
    viewModel.savePseudo()
    advanceUntilIdle()

    assertEquals(0, pseudos.saveCount)
    assertNull(viewModel.state.value.pseudoError)
  }

  @Test
  fun changedPhotoIsUploadedForTheUserThenTheSessionIsRefreshedAndTheCopyDeleted() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()
    viewModel.changePhoto("content://a", fromCamera = true)
    assertTrue(viewModel.state.value.isUploadingPhoto)
    advanceUntilIdle()
    assertEquals(listOf("content://a" to true), files.copies)
    assertEquals(listOf("user-1" to File("copy-a")), photos.uploads)
    assertEquals(1, auth.refreshes)
    assertEquals(listOf(File("copy-a")), files.deleted)
    assertFalse(viewModel.state.value.isUploadingPhoto)
    assertNull(viewModel.state.value.photoError)
  }

  @Test
  fun invalidPhotoShowsItsErrorWithoutUploading() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()
    files.failure = IllegalArgumentException("too large")
    viewModel.changePhoto("content://a", fromCamera = false)
    assertEquals(ProfilePhotoError.INVALID_PHOTO, viewModel.state.value.photoError)
    assertTrue(photos.uploads.isEmpty())
  }

  @Test
  fun failedUploadKeepsTheCopyForRetryAndANewPhotoReplacesIt() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()
    photos.next = { Result.failure(IllegalStateException("offline")) }
    viewModel.changePhoto("content://a", fromCamera = false)
    advanceUntilIdle()
    assertEquals(ProfilePhotoError.UPLOAD_FAILED, viewModel.state.value.photoError)
    viewModel.retryPhotoUpload()
    advanceUntilIdle()
    assertEquals(2, photos.uploads.size)
    assertTrue(files.deleted.isEmpty())
    assertEquals(0, auth.refreshes)

    photos.next = { Result.success("url") }
    viewModel.changePhoto("content://b", fromCamera = false)
    advanceUntilIdle()
    assertEquals(listOf(File("copy-a"), File("copy-b")), files.deleted)
    assertEquals(File("copy-b"), photos.uploads.last().second)
    assertNull(viewModel.state.value.photoError)
    assertEquals(1, auth.refreshes)
  }

  @Test
  fun photoChangesAreIgnoredWhileUploadingAndClearingCancelsBeforeDeleting() = runTest {
    val store = ViewModelStore()
    val viewModel = createViewModel().also { store.put("edit_profile", it) }
    advanceUntilIdle()
    photos.next = { CompletableDeferred<Result<String>>().await() }
    viewModel.changePhoto("content://a", fromCamera = false)
    advanceUntilIdle()
    viewModel.changePhoto("content://b", fromCamera = false)
    viewModel.retryPhotoUpload()
    advanceUntilIdle()
    assertEquals(1, files.copies.size)
    assertEquals(1, photos.uploads.size)

    store.clear()
    advanceUntilIdle()
    assertEquals(listOf("upload cancelled", "delete copy-a"), events)
  }

  /** Copies `scheme://name` to `copy-name`; deletions are recorded, never performed. */
  private inner class FakeFiles : PersonalPhotoFiles {
    val copies = mutableListOf<Pair<String, Boolean>>()
    val deleted = mutableListOf<File>()
    var failure: Exception? = null

    override suspend fun copy(uri: String, fromCamera: Boolean): File {
      copies += uri to fromCamera
      failure?.let { throw it }
      return File("copy-" + uri.substringAfter("://"))
    }

    override suspend fun delete(file: File) {
      events += "delete ${file.name}"
      deleted += file
    }
  }

  private inner class FakePhotoRepository : ProfilePhotoRepository {
    val uploads = mutableListOf<Pair<String, File>>()
    var next: suspend () -> Result<String> = { Result.success("url") }

    override suspend fun upload(expectedUid: String, source: File): Result<String> {
      uploads += expectedUid to source
      try {
        return next()
      } catch (cancelled: CancellationException) {
        events += "upload cancelled"
        throw cancelled
      }
    }
  }

  private class FakeAuthRepository(session: AuthSession?) : AuthRepository {
    val sessionState = MutableStateFlow(session)
    override val session: Flow<AuthSession?> = sessionState
    var refreshes = 0

    override fun refreshSession() {
      refreshes++
    }

    override suspend fun signIn(email: String, password: String) = Unit

    override suspend fun signUp(email: String, password: String, displayName: String) = Unit

    override suspend fun signInWithGoogle(idToken: String) = Unit

    override fun signOut() {
      sessionState.value = null
    }
  }

  private class RecordingPseudoRepository(var stored: String? = null) : PseudoRepository {
    var getFailure: Exception? = null
    var setFailure: Exception? = null
    var saveCount = 0
    val cancelledUserIds = mutableListOf<String>()
    val pseudosByUser = mutableMapOf<String, String?>()
    val getGates = mutableMapOf<String, CompletableDeferred<Unit>>()
    val requestedUserIds = mutableListOf<String>()

    override fun observePseudos(userIds: Set<String>): Flow<Map<String, String>> =
        flowOf(emptyMap())

    override suspend fun getPseudo(userId: String): String? {
      requestedUserIds += userId
      try {
        getGates[userId]?.await()
      } catch (cancelled: CancellationException) {
        cancelledUserIds += userId
        throw cancelled
      }
      getFailure?.let { throw it }
      return if (pseudosByUser.containsKey(userId)) pseudosByUser[userId] else stored
    }

    override suspend fun setPseudo(userId: String, pseudo: String) {
      saveCount++
      setFailure?.let { throw it }
      stored = pseudo.trim()
      pseudosByUser[userId] = stored
    }
  }
}
