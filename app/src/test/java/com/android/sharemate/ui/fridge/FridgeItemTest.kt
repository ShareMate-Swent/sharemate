// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.SuccessResult
import coil.size.Size
import com.android.sharemate.model.item.Item
import com.android.sharemate.model.item.ItemImage
import com.android.sharemate.model.item.ItemImageOrigin
import com.android.sharemate.resources.C
import com.android.sharemate.ui.theme.SampleAppTheme
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FridgeItemTest {
  @get:Rule val compose = createComposeRule()
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val pending = CopyOnWriteArrayList<PendingImage>()
  private val loaders = mutableListOf<ImageLoader>()
  private val files = mutableListOf<File>()
  private val item = mutableStateOf(Item(id = "milk", name = "Milk"))

  @After
  fun tearDown() {
    loaders.forEach { it.shutdown() }
    files.forEach { it.delete() }
  }

  private fun controlledLoader(): ImageLoader =
      ImageLoader.Builder(context)
          .components {
            add(
                Interceptor { chain ->
                  val request = PendingImage(chain.request.data.toString(), chain.size)
                  pending += request
                  if (request.succeeds.await()) {
                    SuccessResult(ColorDrawable(Color.RED), chain.request, DataSource.MEMORY)
                  } else {
                    ErrorResult(null, chain.request, IOException("Image unavailable"))
                  }
                })
          }
          .build()
          .also { loaders += it }

  private fun localLoader(): ImageLoader =
      ImageLoader.Builder(context)
          .components {
            add(
                Interceptor { chain ->
                  check(!chain.request.data.toString().startsWith("http"))
                  chain.proceed(chain.request)
                })
          }
          .build()
          .also { loaders += it }

  private fun show(
      loader: ImageLoader = controlledLoader(),
      links: MutableList<String> = mutableListOf()
  ) {
    compose.setContent {
      CompositionLocalProvider(
          LocalUriHandler provides
              object : UriHandler {
                override fun openUri(uri: String) {
                  links += uri
                }
              }) {
            SampleAppTheme(dynamicColor = false) {
              FridgeItem(item = item.value, imageLoader = loader)
            }
          }
    }
  }

  private fun remote(reference: String = "https://images.example.org/milk.jpg") =
      ItemImage(origin = ItemImageOrigin.REMOTE_IMAGE, reference = reference)

  private fun waitFor(tag: String) {
    compose.waitUntil(5000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    compose.onNodeWithTag(tag).assertIsDisplayed()
  }

  private fun waitForRequest(count: Int = 1) {
    compose.waitForIdle()
    compose.waitUntil(5000) { pending.size == count }
  }

  @Test
  fun absentImageUsesLocalPlaceholderWithoutLoading() {
    show()
    compose
        .onNodeWithTag(C.Tag.fridge_image_placeholder)
        .assertIsDisplayed()
        .assertWidthIsEqualTo(80.dp)
        .assertHeightIsEqualTo(80.dp)
    compose.onNodeWithTag(C.Tag.fridge_image_loading).assertDoesNotExist()
    assertTrue(pending.isEmpty())
  }

  @Test
  fun loadingThenSuccessUsesTheStoredReferenceAndThumbnailDimensions() {
    item.value = item.value.copy(image = remote())
    show()
    waitForRequest()
    compose.onNodeWithTag(C.Tag.fridge_image_loading).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.fridge_image).assertDoesNotExist()
    assertEquals(item.value.image!!.reference, pending.single().source)
    val pixels = (80 * context.resources.displayMetrics.density).toInt()
    assertEquals(Size(pixels, pixels), pending.single().size)

    pending.single().succeeds.complete(true)
    waitFor(C.Tag.fridge_image)
    compose.onNodeWithContentDescription("Photo of Milk").assertIsDisplayed()
    compose
        .onNodeWithTag(C.Tag.fridge_image)
        .assertWidthIsEqualTo(80.dp)
        .assertHeightIsEqualTo(80.dp)
    compose.onNodeWithTag(C.Tag.fridge_image_loading).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.fridge_image_placeholder).assertDoesNotExist()
    compose.runOnIdle { item.value = item.value.copy(category = "Dairy") }
    compose.waitForIdle()
    assertEquals(1, pending.size)
  }

  @Test
  fun failedDownloadUsesPlaceholder() {
    item.value = item.value.copy(image = remote())
    show()
    waitForRequest()
    pending.single().succeeds.complete(false)
    waitFor(C.Tag.fridge_image_placeholder)
    compose.onNodeWithTag(C.Tag.fridge_image).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.fridge_image_loading).assertDoesNotExist()
  }

  @Test
  fun inaccessiblePersonalContentUriUsesPlaceholder() {
    item.value =
        item.value.copy(image = ItemImage(reference = "content://missing.provider/photo/42"))
    show(localLoader())
    waitFor(C.Tag.fridge_image_placeholder)
    compose.onNodeWithTag(C.Tag.fridge_image).assertDoesNotExist()
    compose.onNodeWithText("Image search powered by Openverse").assertDoesNotExist()
  }

  @Test
  fun invalidReferenceUsesPlaceholder() {
    item.value = item.value.copy(image = remote("invalid://image"))
    show(localLoader())
    waitFor(C.Tag.fridge_image_placeholder)
  }

  @Test
  fun blankReferenceDoesNotStartLoading() {
    item.value = item.value.copy(image = ItemImage(reference = " \t "))
    show()
    waitFor(C.Tag.fridge_image_placeholder)
    assertTrue(pending.isEmpty())
  }

  @Test
  fun changingSourceRemovesOldPhotoUntilNewPhotoLoads() {
    item.value = item.value.copy(image = remote())
    show()
    waitForRequest()
    pending[0].succeeds.complete(true)
    waitFor(C.Tag.fridge_image)

    compose.runOnIdle {
      item.value =
          item.value.copy(name = "Bread", image = ItemImage(reference = "content://photos/bread"))
    }
    waitForRequest(2)
    compose.onNodeWithContentDescription("Photo of Milk").assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.fridge_image).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.fridge_image_loading).assertIsDisplayed()
    assertEquals("content://photos/bread", pending[1].source)
    pending[1].succeeds.complete(true)
    waitFor(C.Tag.fridge_image)
    compose.onNodeWithContentDescription("Photo of Bread").assertIsDisplayed()

    compose.runOnIdle { item.value = Item(id = "other", name = "Rice") }
    waitFor(C.Tag.fridge_image_placeholder)
    compose.onNodeWithTag(C.Tag.fridge_image).assertDoesNotExist()
  }

  @Test
  fun changingItemDuringLookupDiscardsLateResults() {
    item.value = item.value.copy(image = remote())
    show()
    waitForRequest()
    compose.runOnIdle {
      item.value =
          Item(id = "bread", name = "Bread", image = remote("https://images.example.org/bread.jpg"))
    }
    waitForRequest(2)
    pending[1].succeeds.complete(false)
    waitFor(C.Tag.fridge_image_placeholder)
    pending[0].succeeds.complete(true)
    compose.waitForIdle()
    compose.onNodeWithTag(C.Tag.fridge_image_placeholder).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.fridge_image).assertDoesNotExist()
  }

  @Test
  fun localFilePathAndFileUriAreDecodedByCoil() {
    val file = File.createTempFile("food", ".png", context.cacheDir).also { files += it }
    val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.BLUE)
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
    item.value = item.value.copy(image = ItemImage(reference = file.absolutePath))
    show(localLoader())
    waitFor(C.Tag.fridge_image)
    compose.runOnIdle {
      item.value = item.value.copy(image = ItemImage(reference = file.toURI().toString()))
    }
    waitFor(C.Tag.fridge_image)
    compose.onNodeWithTag(C.Tag.fridge_image_placeholder).assertDoesNotExist()
  }

  @Test
  fun remoteCreditsAndLinksAreAvailableAndPersonalPhotosHaveNoCredits() {
    val links = mutableListOf<String>()
    item.value =
        item.value.copy(
            image =
                remote()
                    .copy(
                        title = "Fresh milk",
                        author = "Jane Doe",
                        sourceName = "Photo library",
                        sourceUrl = "https://example.org/photo",
                        license = "by",
                        licenseVersion = "4.0",
                        licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
                        authorUrl = "https://example.org/jane"))
    show(links = links)
    compose.onNodeWithText("Fresh milk · Jane Doe · Photo library · CC BY 4.0").assertIsDisplayed()
    compose.onNodeWithText("Image search powered by Openverse").assertIsDisplayed()
    listOf("Image source", "License", "Author").forEach {
      compose.onNodeWithText(it).performClick()
    }
    assertEquals(
        listOf(
            item.value.image!!.sourceUrl,
            item.value.image!!.licenseUrl,
            item.value.image!!.authorUrl),
        links)
    compose.runOnIdle {
      item.value = item.value.copy(image = ItemImage(reference = "content://photos/milk"))
    }
    compose.onNodeWithText("Image source").assertDoesNotExist()
    compose.onNodeWithText("License").assertDoesNotExist()
    compose.onNodeWithText("Image search powered by Openverse").assertDoesNotExist()
  }

  private class PendingImage(val source: String, val size: Size) {
    val succeeds = CompletableDeferred<Boolean>()
  }
}
