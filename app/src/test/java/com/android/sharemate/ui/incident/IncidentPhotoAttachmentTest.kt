// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.incident

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.android.sharemate.R
import com.android.sharemate.model.image.AndroidPersonalPhotoFiles
import com.android.sharemate.model.image.PersonalPhotoFiles
import com.android.sharemate.model.image.PersonalPhotoRepository
import com.android.sharemate.model.image.TemporaryPhotoStore
import com.android.sharemate.ui.photo.PhotoPickerTestTags
import com.android.sharemate.ui.photo.PhotoPickerViewModel
import com.android.sharemate.ui.photo.PhotoSource
import com.google.common.util.concurrent.Futures
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
/**
 * Exercises the incident attachment through the shared camera/gallery dialog and real file copies.
 */
class IncidentPhotoAttachmentTest {
  @get:Rule val compose = createComposeRule()
  private val context = RuntimeEnvironment.getApplication()
  private val source = Sources()
  private val picker = Picker()
  private val vm = IncidentPhotoViewModel(AndroidPersonalPhotoFiles(context))
  private val camera = PhotoPickerViewModel(source, SavedStateHandle())
  private val viewModels =
      ViewModelStore().apply {
        put("draft", vm)
        put("camera", camera)
      }

  @After
  fun cleanUp() {
    compose.runOnIdle { viewModels.clear() }
  }

  @Test
  fun grantedPermissionAndCaptureButtonsProduceAConfirmedCameraAttachment() {
    shadowOf(context).denyPermissions(Manifest.permission.CAMERA)
    picker.grantCamera = true
    val provider = mock(ProcessCameraProvider::class.java)
    val capture = mock(ImageCapture::class.java)
    doAnswer {
          source.write(Uri.parse(checkNotNull(source.lastCapture)))
          it.getArgument<ImageCapture.OnImageSavedCallback>(2)
              .onImageSaved(ImageCapture.OutputFileResults(null))
          null
        }
        .`when`(capture)
        .takePicture(
            any(ImageCapture.OutputFileOptions::class.java),
            any(Executor::class.java),
            any(ImageCapture.OnImageSavedCallback::class.java))
    mockStatic(ProcessCameraProvider::class.java).use { providers ->
      providers
          .`when`<com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider>> {
            ProcessCameraProvider.getInstance(any(Context::class.java))
          }
          .thenReturn(Futures.immediateFuture(provider))
      mockConstruction(ImageCapture.Builder::class.java) { builder, _ ->
            `when`(builder.build()).thenReturn(capture)
          }
          .use {
            render()
            compose.onNodeWithTag(IncidentPhotoTags.SELECT).performClick()
            compose.onNodeWithTag(PhotoPickerTestTags.CAMERA).performScrollTo().performClick()
            compose.onNodeWithTag(PhotoPickerTestTags.VIEWFINDER).assertIsDisplayed()
            compose
                .onNodeWithTag(PhotoPickerTestTags.CAPTURE)
                .assertIsEnabled()
                .performScrollTo()
                .performClick()
            compose.onNodeWithTag(PhotoPickerTestTags.PREVIEW).assertIsDisplayed()
            compose.runOnIdle {
              assertEquals(1, picker.permissionRequests)
              assertTrue(camera.state.value.selection?.source == PhotoSource.CAMERA)
              assertNull(vm.state.value.file) // Nothing is attached before confirmation.
            }
            confirm()
            compose.onNodeWithTag(IncidentPhotoTags.PREVIEW).assertIsDisplayed()
            compose.runOnIdle {
              val attachment = checkNotNull(vm.state.value.file)
              assertTrue(attachment.exists())
              assertEquals(
                  listOf(255, 216), attachment.readBytes().take(2).map { it.toInt() and 255 })
              assertFalse(File(Uri.parse(checkNotNull(source.lastCapture)).path!!).exists())
            }
            assertTextDraft()
          }
    }
  }

  @Test
  fun gallerySelectionShowsPreviewAndReplacementAndRemovalKeepTextDraft() {
    render()
    selectGallery("content://images/first")
    confirm()
    val first = vm.state.value.file!!
    compose.onNodeWithTag(IncidentPhotoTags.PREVIEW).assertIsDisplayed()
    compose.onNodeWithContentDescription("Incident photo preview").assertExists()
    assertTrue(first.exists())

    selectGallery("content://images/second")
    confirm()
    compose.runOnIdle {
      assertNotEquals(first, vm.state.value.file)
      assertFalse(first.exists())
    }
    val second = vm.state.value.file!!
    compose.onNodeWithTag(IncidentPhotoTags.REMOVE).performClick()
    compose.waitUntil { !second.exists() }
    compose.onNodeWithTag(IncidentPhotoTags.PREVIEW).assertDoesNotExist()
    assertTextDraft()
  }

  @Test
  fun cameraCaptureIsPreviewedAndCancellationPreservesConfirmedAttachment() {
    render()
    selectGallery("content://images/original")
    confirm()
    val original = vm.state.value.file
    compose.onNodeWithTag(IncidentPhotoTags.SELECT).performClick()
    compose.runOnIdle { camera.capture { uri -> source.write(Uri.parse(uri)) } }
    compose.waitUntil { camera.state.value.selection?.uri != null && !camera.state.value.busy }
    compose.onNodeWithTag(PhotoPickerTestTags.PREVIEW).assertIsDisplayed()
    compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).performScrollTo().performClick()
    compose.runOnIdle { assertEquals(original, vm.state.value.file) }
    assertTextDraft()

    compose.onNodeWithTag(IncidentPhotoTags.SELECT).performClick()
    confirm()
    compose.runOnIdle {
      assertNotEquals(original, vm.state.value.file)
      assertFalse(original!!.exists())
      assertTrue(vm.state.value.file!!.exists())
    }
    compose.onNodeWithTag(IncidentPhotoTags.PREVIEW).assertIsDisplayed()
  }

  @Test
  fun galleryCancellationAndDeniedCameraPreservePhotoAndTextAndAllowRecovery() {
    shadowOf(context).denyPermissions(Manifest.permission.CAMERA)
    render()
    selectGallery("content://images/original")
    confirm()
    val original = vm.state.value.file
    selectGallery(null)
    compose.runOnIdle { assertEquals(original, vm.state.value.file) }
    compose.onNodeWithTag(PhotoPickerTestTags.CAMERA).performScrollTo().performClick()
    compose.onNodeWithText("Camera access is needed", substring = true).assertExists()
    compose.onNodeWithTag(PhotoPickerTestTags.GALLERY).assertIsEnabled()
    compose.runOnIdle {
      assertEquals(1, picker.permissionRequests)
      assertEquals(original, vm.state.value.file)
      assertFalse(camera.state.value.failed)
    }
    compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).performScrollTo().performClick()
    assertTextDraft()
    selectGallery("content://images/recovered")
    confirm()
    compose.runOnIdle { assertNotEquals(original, vm.state.value.file) }
  }

  @Test
  fun invalidImageConfirmationKeepsPreviousAttachmentAndShowsError() {
    render()
    selectGallery("content://images/original")
    confirm()
    val original = vm.state.value.file
    selectGallery("content://images/invalid")
    compose.runOnIdle {
      File(Uri.parse(camera.state.value.selection?.uri!!).path!!).writeText("not an image")
    }
    compose.onNodeWithTag(PhotoPickerTestTags.CONFIRM).performScrollTo().performClick()
    await { !camera.state.value.busy && vm.state.value.rejected }
    compose.runOnIdle {
      assertEquals(original, vm.state.value.file)
      assertTrue(original!!.exists())
      assertTrue(camera.state.value.visible)
    }
    compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).performScrollTo().performClick()
    compose
        .onNodeWithTag(IncidentPhotoTags.ERROR)
        .assertIsDisplayed()
        .assertTextEquals(importError())
    compose.onNodeWithTag(IncidentPhotoTags.SELECT).performClick()
    compose.runOnIdle { assertFalse(vm.state.value.rejected) }
    compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).performScrollTo().performClick()
    compose.onNodeWithTag(IncidentPhotoTags.ERROR).assertDoesNotExist()
    compose.runOnIdle {
      assertEquals(original, vm.state.value.file)
      assertTrue(original!!.exists())
    }
    assertTextDraft()
  }

  @Test
  fun importingDisablesSelectAndRemoveUntilTheReplacementIsConfirmed() {
    val files = ControlledFiles(AndroidPersonalPhotoFiles(context))
    val draft = IncidentPhotoViewModel(files)
    viewModels.put("controlled-draft", draft)
    render(draft)
    selectGallery("content://images/original")
    confirm()
    val original = draft.state.value.file
    val gate = CompletableDeferred<Unit>()
    files.gate = gate
    selectGallery("content://images/replacement")
    compose.onNodeWithTag(PhotoPickerTestTags.CONFIRM).performScrollTo().performClick()
    await { draft.state.value.importing }
    compose.onNodeWithTag(IncidentPhotoTags.SELECT).assertIsNotEnabled()
    compose.onNodeWithTag(IncidentPhotoTags.REMOVE).assertIsNotEnabled()
    compose.onNodeWithTag(IncidentPhotoTags.IMPORTING).assertExists()
    compose.runOnIdle {
      draft.clearRejection()
      assertTrue(draft.state.value.importing)
      assertEquals(original, draft.state.value.file)
      gate.complete(Unit)
    }
    await { !draft.state.value.importing && !camera.state.value.busy }
    compose.onNodeWithTag(IncidentPhotoTags.SELECT).assertIsEnabled()
    compose.onNodeWithTag(IncidentPhotoTags.REMOVE).assertIsEnabled()
    compose.onNodeWithTag(IncidentPhotoTags.IMPORTING).assertDoesNotExist()
    compose.runOnIdle {
      assertNotEquals(original, draft.state.value.file)
      assertFalse(original!!.exists())
    }
  }

  @Test
  fun ioFailureShowsGenericRetryGuidanceWithTheConfiguredSizeLimit() {
    val files = ControlledFiles(AndroidPersonalPhotoFiles(context))
    files.failure = IOException("Disk full")
    val draft = IncidentPhotoViewModel(files)
    viewModels.put("controlled-draft", draft)
    render(draft)
    selectGallery("content://images/unreadable")
    compose.onNodeWithTag(PhotoPickerTestTags.CONFIRM).performScrollTo().performClick()
    await { draft.state.value.rejected && !camera.state.value.busy }
    compose.onNodeWithTag(PhotoPickerTestTags.CANCEL).performScrollTo().performClick()
    compose
        .onNodeWithTag(IncidentPhotoTags.ERROR)
        .assertIsDisplayed()
        .assertTextEquals(importError())
    compose.runOnIdle { assertNull(draft.state.value.file) }
  }

  @Test
  fun factoryRetainsTheKeyedDraftAcrossRecompositionAndCleansItsPrivateCopy() {
    val owner =
        object : ViewModelStoreOwner {
          override val viewModelStore = viewModels
        }
    var revision by mutableStateOf(0)
    lateinit var first: IncidentPhotoViewModel
    lateinit var sameKey: IncidentPhotoViewModel
    lateinit var otherKey: IncidentPhotoViewModel
    compose.setContent {
      CompositionLocalProvider(
          LocalViewModelStoreOwner provides owner,
          LocalActivityResultRegistryOwner provides picker) {
            first = incidentPhotoViewModel("first-incident")
            sameKey = incidentPhotoViewModel("first-incident")
            otherKey = incidentPhotoViewModel("other-incident")
            MaterialTheme {
              Column {
                Text("Revision $revision")
                IncidentPhotoAttachment(first, picker = camera)
              }
            }
          }
    }
    val originalOwner = first
    compose.runOnIdle {
      assertSame(first, sameKey)
      assertNotSame(first, otherKey)
    }
    selectGallery("content://images/factory")
    confirm()
    val file = checkNotNull(first.state.value.file)
    assertTrue(file.exists())
    compose.runOnIdle { revision++ }
    compose.runOnIdle {
      assertSame(originalOwner, first)
      assertEquals(file, first.state.value.file)
      assertNull(otherKey.state.value.file)
      viewModels.clear()
    }
    await { !file.exists() }
  }

  private fun importError() =
      context.getString(R.string.incident_photo_invalid, PersonalPhotoRepository.MAX_MEBIBYTES)

  private fun render(draft: IncidentPhotoViewModel = vm) {
    compose.setContent {
      CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
        MaterialTheme {
          Column {
            var description by remember { mutableStateOf("Broken fridge shelf") }
            OutlinedTextField(
                description, { description = it }, modifier = Modifier.testTag("draft"))
            IncidentPhotoAttachment(draft, picker = camera)
          }
        }
      }
    }
  }

  private fun selectGallery(uri: String?) {
    compose.onNodeWithTag(IncidentPhotoTags.SELECT).performClick()
    compose.onNodeWithTag(PhotoPickerTestTags.GALLERY).performScrollTo().performClick()
    compose.runOnIdle { picker.deliver(uri) }
    await { !camera.state.value.busy }
  }

  private fun confirm() {
    compose.onNodeWithTag(PhotoPickerTestTags.CONFIRM).performScrollTo().performClick()
    await { !camera.state.value.busy }
    assertFalse("Confirmation failed: ${vm.state.value}", camera.state.value.visible)
    compose.waitForIdle()
  }

  private fun await(condition: () -> Boolean) {
    compose.waitUntil(5_000) {
      // Pump the Android main loop so callbacks from the real IO file copy can resume.
      compose.waitForIdle()
      condition()
    }
  }

  private fun assertTextDraft() {
    compose.onNodeWithTag("draft").assertTextContains("Broken fridge shelf")
  }

  private class ControlledFiles(private val delegate: PersonalPhotoFiles) : PersonalPhotoFiles {
    var gate: CompletableDeferred<Unit>? = null
    var failure: Exception? = null

    override suspend fun copy(uri: String, fromCamera: Boolean): File {
      gate?.await()
      failure?.let { throw it }
      return delegate.copy(uri, fromCamera)
    }

    override suspend fun delete(file: File) = delegate.delete(file)
  }

  private class Picker : ActivityResultRegistry(), ActivityResultRegistryOwner {
    override val activityResultRegistry = this
    private var request: Int? = null
    var permissionRequests = 0
    var grantCamera = false

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
      when (contract) {
        is ActivityResultContracts.RequestPermission -> {
          assertEquals(Manifest.permission.CAMERA, input)
          permissionRequests++
          if (grantCamera)
              shadowOf(RuntimeEnvironment.getApplication())
                  .grantPermissions(Manifest.permission.CAMERA)
          dispatchResult(requestCode, grantCamera)
        }
        is ActivityResultContracts.PickVisualMedia -> {
          assertEquals(
              ActivityResultContracts.PickVisualMedia.ImageOnly,
              (input as PickVisualMediaRequest).mediaType)
          request = requestCode
        }
        else -> error("Unexpected external activity")
      }
    }

    fun deliver(uri: String?) {
      dispatchResult(checkNotNull(request), uri?.let(Uri::parse))
      request = null
    }
  }

  private class Sources : TemporaryPhotoStore {
    private val directory = File(RuntimeEnvironment.getApplication().cacheDir, "barcode-photos")
    var lastCapture: String? = null
      private set

    override suspend fun createCapture(): String {
      directory.mkdirs()
      return Uri.fromFile(File.createTempFile("incident-", ".png", directory)).toString().also {
        lastCapture = it
      }
    }

    override suspend fun importImage(uri: String): String =
        createCapture().also { write(Uri.parse(it)) }

    override suspend fun validate(uri: String) {
      check(File(Uri.parse(uri).path!!).exists())
    }

    override suspend fun delete(uri: String) {
      File(Uri.parse(uri).path!!).delete()
    }

    fun write(uri: Uri) {
      val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
      File(uri.path!!).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
      bitmap.recycle()
    }
  }
}
