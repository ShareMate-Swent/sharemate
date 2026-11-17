// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
package com.android.sharemate.ui.fridge

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.SavedStateHandle
import com.android.sharemate.R
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.image.BarcodePhotoStore
import com.android.sharemate.model.image.PersonalPhotoFiles
import com.android.sharemate.model.image.PersonalPhotoReference
import com.android.sharemate.model.image.PersonalPhotoRepository
import com.android.sharemate.model.item.FakeItemRepository
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemImage
import com.android.sharemate.resources.C
import com.android.sharemate.ui.barcode.BarcodePhotoTestTags
import com.android.sharemate.ui.barcode.BarcodePhotoViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [34], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class FridgePersonalPhotoUiTest {
  @get:Rule val compose = createComposeRule()

  private val testApplication
    get() = RuntimeEnvironment.getApplication()

  @get:Rule val temporary = TemporaryFolder()
  private val scope = CoroutineScope(Dispatchers.Main.immediate)
  private val first = reference("milk")
  private val second = reference("yogurt")
  private val denied = reference("bread")
  private var copyPhoto: suspend (String) -> File = { error("Unexpected import: $it") }
  private val copyOrigins = mutableListOf<Boolean>()
  private var loadPhoto: suspend (PersonalPhotoReference) -> Result<ByteArray> = {
    error("Unexpected download: $it")
  }
  private val photos =
      PersonalPhotoController(
          FakeItemRepository(),
          object : PersonalPhotoRepository {
            override suspend fun load(reference: PersonalPhotoReference) = loadPhoto(reference)

            override suspend fun upload(itemId: String, source: File) =
                Result.failure<PersonalPhotoReference>(IOException("Storage unavailable"))

            override suspend fun delete(reference: PersonalPhotoReference) = Result.success(Unit)

            override suspend fun deleteAttached(itemId: String) = Result.success(Unit)
          },
          object : PersonalPhotoFiles {
            override suspend fun copy(uri: String, fromCamera: Boolean): File {
              copyOrigins += fromCamera
              return copyPhoto(uri)
            }

            override suspend fun delete(file: File) {
              file.delete()
            }
          },
          scope)

  @After
  fun tearDown() {
    scope.cancel()
  }

  @Test
  fun defaultPhotoEntryHonorsDisabledStateAndOpensTheSourceMenu() {
    val enabled = mutableStateOf(false)
    compose.setContent {
      MaterialTheme {
        Column { PersonalPhotoPicker(photos.draft.collectAsState().value, photos, enabled.value) }
      }
    }
    val entry = compose.onNodeWithText(testApplication.getString(R.string.personal_photo_add))
    entry.assertIsNotEnabled()
    compose.runOnIdle { enabled.value = true }
    entry.performClick()
    compose.onNodeWithTag(BarcodePhotoTestTags.CAMERA).assertIsEnabled()
    compose.onNodeWithTag(BarcodePhotoTestTags.GALLERY).assertIsEnabled()
    compose.onNodeWithTag(BarcodePhotoTestTags.CANCEL).performScrollTo().performClick()
    entry.assertIsEnabled()
    compose.runOnIdle { assertEquals(PersonalPhotoDraft(), photos.draft.value) }
  }

  @Test
  fun cameraPreviewCancellationPreservesDraftAndConfirmationReplacesIt() {
    val previous = temporary.newFile().apply { writeBytes(png(16, 8)) }
    val replacement = temporary.newFile().apply { writeBytes(png(8, 16)) }
    val source = temporary.newFile()
    val camera =
        BarcodePhotoViewModel(
            object : BarcodePhotoStore {
              override suspend fun createCapture() = Uri.fromFile(source).toString()

              override suspend fun importImage(uri: String): String = error("Unexpected gallery")

              override suspend fun validate(uri: String) = Unit

              override suspend fun delete(uri: String) {
                source.delete()
              }
            },
            SavedStateHandle())
    copyPhoto = { if (it.startsWith("content:")) previous else replacement }
    compose.setContent {
      MaterialTheme {
        Column { PersonalPhotoPicker(photos.draft.collectAsState().value, photos, true, camera) }
      }
    }
    compose.runOnIdle { photos.select("content://gallery/previous") }
    compose.onNodeWithText(testApplication.getString(R.string.personal_photo_change)).performClick()
    compose.runOnIdle {
      camera.capture { uri -> File(requireNotNull(Uri.parse(uri).path)).writeBytes(png(4, 4)) }
    }
    compose.onNodeWithTag(BarcodePhotoTestTags.PREVIEW).assertIsDisplayed()
    compose.onNodeWithTag(BarcodePhotoTestTags.CANCEL).performScrollTo().performClick()
    compose.runOnIdle {
      assertEquals(previous, photos.draft.value.file)
      assertTrue(previous.exists())
    }
    compose.onNodeWithText(testApplication.getString(R.string.personal_photo_change)).performClick()
    compose.onNodeWithTag(BarcodePhotoTestTags.CONFIRM).performScrollTo().performClick()
    compose.waitUntil(10_000) { photos.draft.value.file == replacement && !source.exists() }
    compose
        .onNodeWithContentDescription(testApplication.getString(R.string.personal_photo_preview))
        .assertIsDisplayed()
    compose.runOnIdle {
      assertFalse(previous.exists())
      assertEquals(listOf(false, true), copyOrigins)
    }
  }

  @Test
  fun galleryKeepsPreviewOnCancellationAndRejectionAndAllowsRemoval() {
    val selected = temporary.newFile().apply { writeBytes(png(16, 8)) }
    val source = temporary.newFile().apply { writeBytes(png(16, 8)) }
    val rejectedSource = temporary.newFile().apply { writeBytes(png(8, 16)) }
    val pendingCopy = CompletableDeferred<File>()
    val pendingGalleryImport = CompletableDeferred<String>()
    val picker = GalleryResultRegistry()
    val enabled = mutableStateOf(true)
    val imports = mutableListOf<String>()
    val sourceUri = Uri.fromFile(source).toString()
    val camera =
        BarcodePhotoViewModel(
            object : BarcodePhotoStore {
              override suspend fun createCapture(): String = error("Unexpected camera")

              override suspend fun importImage(uri: String) =
                  if (uri.endsWith("/valid")) pendingGalleryImport.await()
                  else Uri.fromFile(rejectedSource).toString()

              override suspend fun validate(uri: String) = Unit

              override suspend fun delete(uri: String) {
                File(requireNotNull(Uri.parse(uri).path)).delete()
              }
            },
            SavedStateHandle())
    copyPhoto = { uri ->
      imports += uri
      if (uri == sourceUri) pendingCopy.await() else throw IOException("Unreadable")
    }
    compose.setContent {
      CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
        MaterialTheme {
          Column {
            PersonalPhotoPicker(photos.draft.collectAsState().value, photos, enabled.value, camera)
          }
        }
      }
    }
    val choose = compose.onNodeWithText(testApplication.getString(R.string.personal_photo_add))
    val change = compose.onNodeWithText(testApplication.getString(R.string.personal_photo_change))
    val preview =
        compose.onNodeWithContentDescription(
            testApplication.getString(R.string.personal_photo_preview))
    val remove = compose.onNodeWithText(testApplication.getString(R.string.personal_photo_remove))
    val error = compose.onNodeWithText(testApplication.getString(R.string.personal_photo_invalid))

    compose.onNodeWithText("Choose a personal photo").assertDoesNotExist()
    compose.onNodeWithText("Take a personal photo").assertDoesNotExist()
    compose.runOnIdle { enabled.value = false }
    choose.assertIsNotEnabled()
    compose.runOnIdle { enabled.value = true }

    choose.performClick()
    compose
        .onNodeWithTag(BarcodePhotoTestTags.GALLERY)
        .assertIsEnabled()
        .performScrollTo()
        .performClick()
    compose.runOnIdle { picker.deliver(null) }
    compose.runOnIdle {
      assertTrue(imports.isEmpty())
      assertFalse(camera.state.value.failed)
    }
    compose.onNodeWithTag(BarcodePhotoTestTags.GALLERY).performScrollTo().performClick()
    compose.runOnIdle { picker.deliver("content://gallery/valid") }
    compose.onNodeWithTag(BarcodePhotoTestTags.GALLERY).assertIsNotEnabled()
    compose.runOnIdle {
      assertFalse(photos.draft.value.importing)
      assertTrue(imports.isEmpty())
      pendingGalleryImport.complete(sourceUri)
    }
    compose.onNodeWithTag(BarcodePhotoTestTags.PREVIEW).assertIsDisplayed()
    compose.runOnIdle { assertTrue(imports.isEmpty()) }
    compose.onNodeWithTag(BarcodePhotoTestTags.CONFIRM).performScrollTo().performClick()
    compose.onNodeWithTag(BarcodePhotoTestTags.CONFIRM).assertIsNotEnabled()
    compose.runOnIdle { pendingCopy.complete(selected) }
    preview.assertIsDisplayed()
    choose.assertDoesNotExist()
    change.assertIsEnabled()
    compose.runOnIdle { assertFalse(source.exists()) }

    change.performClick()
    compose.onNodeWithTag(BarcodePhotoTestTags.GALLERY).performScrollTo().performClick()
    compose.runOnIdle { picker.deliver(null) }
    compose.onNodeWithTag(BarcodePhotoTestTags.CANCEL).performScrollTo().performClick()
    error.assertDoesNotExist()
    preview.assertExists()

    change.performClick()
    compose.onNodeWithTag(BarcodePhotoTestTags.GALLERY).performScrollTo().performClick()
    compose.runOnIdle { picker.deliver("content://gallery/invalid") }
    compose.onNodeWithTag(BarcodePhotoTestTags.CONFIRM).performScrollTo().performClick()
    compose.onNodeWithText("Unable to use this photo.", substring = true).assertExists()
    compose.onNodeWithTag(BarcodePhotoTestTags.CANCEL).performScrollTo().performClick()
    error.assertIsDisplayed()
    preview.assertExists()
    compose.runOnIdle {
      assertEquals(listOf(sourceUri, Uri.fromFile(rejectedSource).toString()), imports)
      assertEquals(listOf(false, false), copyOrigins)
      assertTrue(selected.exists())
    }

    remove.performClick()
    preview.assertDoesNotExist()
    error.assertDoesNotExist()
    choose.assertIsEnabled()
    compose.runOnIdle { assertFalse(selected.exists()) }
  }

  @Test
  fun changingTheStoredPhotoCancelsTheOldLoadAndDenialClearsTheImage() {
    val requests = mutableListOf<PersonalPhotoReference>()
    val cancelled = CompletableDeferred<Unit>()
    val unavailable = CompletableDeferred<Result<ByteArray>>()
    val bytes = png(512, 256)
    loadPhoto = {
      requests += it
      when (it) {
        first ->
            try {
              awaitCancellation()
            } finally {
              cancelled.complete(Unit)
            }
        second -> Result.success(bytes)
        denied -> unavailable.await()
        else -> error("Unexpected reference: $it")
      }
    }
    val item =
        mutableStateOf(
            Item(
                id = "milk",
                name = "Milk",
                image = ItemImage(reference = first.path),
                householdId = "household-1"))
    val session = mutableStateOf<PersonalPhotoController?>(photos)
    compose.setContent {
      MaterialTheme {
        FridgeScreen(
            uiState = FridgeUiState(items = listOf(item.value), fridgeType = FridgeType.SHARED),
            personalPhotos = session.value,
            expiryStatuses = emptyMap())
      }
    }
    val placeholder = compose.onNodeWithTag(C.Tag.fridge_image_placeholder)
    val image = compose.onNodeWithTag(C.Tag.fridge_image)
    placeholder.assertIsDisplayed()
    compose.runOnIdle {
      assertEquals(listOf(first), requests)
      item.value =
          Item(
              id = "yogurt",
              name = "Yogurt",
              image = ItemImage(reference = second.path),
              householdId = "household-1")
    }

    compose.waitUntil(5_000) {
      compose.onAllNodesWithTag(C.Tag.fridge_image).fetchSemanticsNodes().size == 1
    }
    compose.onNodeWithContentDescription("Photo of Yogurt").assertIsDisplayed()
    placeholder.assertDoesNotExist()
    compose.runOnIdle {
      assertTrue(cancelled.isCompleted)
      item.value =
          Item(
              id = "bread",
              name = "Bread",
              image = ItemImage(reference = denied.path),
              householdId = "household-1")
    }
    placeholder.assertIsDisplayed()
    image.assertDoesNotExist()
    compose.runOnIdle { unavailable.complete(Result.failure(IOException("Access denied"))) }
    placeholder.assertIsDisplayed()
    image.assertDoesNotExist()
    compose.runOnIdle {
      assertEquals(listOf(first, second, denied), requests)
      session.value = null
    }
    placeholder.assertIsDisplayed()
    compose.runOnIdle { assertEquals(listOf(first, second, denied), requests) }
  }

  @Test
  fun failedAttachmentShowsTheSavedItemAndItsPhotoError() {
    val milk = Item(id = "milk", name = "Milk", householdId = "household-1")
    val source = temporary.newFile()
    compose.setContent {
      MaterialTheme {
        FridgeScreen(
            uiState = FridgeUiState(items = listOf(milk), fridgeType = FridgeType.SHARED),
            personalPhotos = photos,
            expiryStatuses = emptyMap())
      }
    }

    compose.runOnIdle { photos.attachCreated(milk.id, milk.name, source) }

    compose
        .onNodeWithText("Milk was saved, but its photo could not be attached.")
        .assertIsDisplayed()
    compose.onNodeWithText("Milk").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.fridge_image_placeholder).assertIsDisplayed()
  }

  private class GalleryResultRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
    override val activityResultRegistry = this
    private var launchedRequest: Int? = null

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
      assertTrue(contract is ActivityResultContracts.PickVisualMedia)
      assertEquals(
          ActivityResultContracts.PickVisualMedia.ImageOnly,
          (input as PickVisualMediaRequest).mediaType)
      launchedRequest = requestCode
    }

    fun deliver(uri: String?) {
      val request = checkNotNull(launchedRequest)
      launchedRequest = null
      dispatchResult(request, uri?.let(Uri::parse))
    }
  }

  private fun reference(itemId: String) =
      PersonalPhotoReference("itemPhotos/$itemId/00000000-0000-0000-0000-000000000001")

  private fun png(width: Int, height: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    return try {
      ByteArrayOutputStream()
          .apply { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, this)) }
          .toByteArray()
    } finally {
      bitmap.recycle()
    }
  }
}
