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
import androidx.core.app.ActivityOptionsCompat
import com.android.sharemate.model.household.FridgeType
import com.android.sharemate.model.image.PersonalPhotoFiles
import com.android.sharemate.model.image.PersonalPhotoReference
import com.android.sharemate.model.image.PersonalPhotoRepository
import com.android.sharemate.model.item.ExpirationDates
import com.android.sharemate.model.item.FakeItemRepository
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemImage
import com.android.sharemate.resources.C
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [34], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class FridgePersonalPhotoUiTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val temporary = TemporaryFolder()
  private val scope = CoroutineScope(Dispatchers.Main.immediate)
  private val first = reference("milk")
  private val second = reference("yogurt")
  private val denied = reference("bread")
  private var copyPhoto: suspend (String) -> File = { error("Unexpected import: $it") }
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
            override suspend fun copy(uri: String) = copyPhoto(uri)

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
  fun galleryKeepsPreviewOnCancellationAndRejectionAndAllowsRemoval() {
    val selected = temporary.newFile().apply { writeBytes(png(16, 8)) }
    val pendingCopy = CompletableDeferred<File>()
    val picker = GalleryResultRegistry()
    val enabled = mutableStateOf(true)
    val imports = mutableListOf<String>()
    copyPhoto = { uri ->
      imports += uri
      if (uri.endsWith("/valid")) pendingCopy.await() else throw IOException("Unreadable")
    }
    compose.setContent {
      CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
        MaterialTheme {
          Column { PersonalPhotoPicker(photos.draft.collectAsState().value, photos, enabled.value) }
        }
      }
    }
    val choose = compose.onNodeWithText("Choose a personal photo")
    val change = compose.onNodeWithText("Change personal photo")
    val preview = compose.onNodeWithContentDescription("Selected personal photo")
    val remove = compose.onNodeWithText("Remove personal photo")
    val error = compose.onNodeWithText("Choose a readable JPEG or PNG photo of at most 5 MiB.")

    choose.performClick()
    compose.runOnIdle { enabled.value = false }
    choose.assertIsNotEnabled()
    compose.runOnIdle { picker.deliver("content://gallery/ignored") }
    compose.runOnIdle {
      assertTrue(imports.isEmpty())
      enabled.value = true
    }

    choose.performClick()
    compose.runOnIdle { picker.deliver("content://gallery/valid") }
    choose.assertIsNotEnabled()
    compose.runOnIdle { pendingCopy.complete(selected) }
    preview.assertIsDisplayed()

    change.performClick()
    compose.runOnIdle { picker.deliver(null) }
    error.assertDoesNotExist()
    preview.assertExists()

    change.performClick()
    compose.runOnIdle { picker.deliver("content://gallery/invalid") }
    error.assertIsDisplayed()
    preview.assertExists()
    compose.runOnIdle {
      assertEquals(listOf("content://gallery/valid", "content://gallery/invalid"), imports)
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
            soonThresholdDays = ExpirationDates.SOON_THRESHOLD_DAYS)
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
            soonThresholdDays = ExpirationDates.SOON_THRESHOLD_DAYS)
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
