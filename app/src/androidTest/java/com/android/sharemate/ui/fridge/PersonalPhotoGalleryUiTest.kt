// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sharemate.model.image.PersonalPhotoDraftStore
import com.android.sharemate.resources.C
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Actual dialog layout is checked on Android, rather than Robolectric's legacy text renderer. */
@RunWith(AndroidJUnit4::class)
class PersonalPhotoGalleryUiTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  @get:Rule val temporary = TemporaryFolder()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val gate = CompletableDeferred<Unit>()
  private lateinit var controller: PersonalPhotoDraftController

  @After
  fun tearDown() {
    if (::controller.isInitialized) controller.close()
    scope.cancel()
  }

  @Test
  fun importDisablesSaveButCancellationOfTheFormRemainsAvailable() {
    val file =
        temporary.newFile("selection.png").apply {
          writeBytes(
              android.util.Base64.decode(
                  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNYxfD/PwAFqgKpvAzkRAAAAABJRU5ErkJggg==",
                  android.util.Base64.DEFAULT))
        }
    val drafts =
        object : PersonalPhotoDraftStore {
          override suspend fun importPhoto(uri: String): File {
            gate.await()
            return file
          }

          override suspend fun delete(file: File) {
            file.delete()
          }
        }
    controller = PersonalPhotoDraftController(drafts, scope)
    var saves = 0
    compose.setContent {
      MaterialTheme {
        AddItemDialog(
            uiState = FridgeUiState(isAddItemDialogOpen = true, itemName = "Milk"),
            onNameChange = {},
            onCategoryChange = {},
            onExpirationDateChange = {},
            onSave = { saves++ },
            onDismiss = {},
            personalPhotoDraft = controller)
      }
    }
    compose.runOnIdle { controller.select("content://photos/selected") }
    compose.onNodeWithTag(C.Tag.fridge_save_item).assertIsDisplayed().assertIsNotEnabled()
    compose.onNodeWithTag(C.Tag.fridge_cancel_item).assertIsDisplayed().assertIsEnabled()
    compose.runOnIdle { gate.complete(Unit) }
    compose
        .onNodeWithTag(C.Tag.fridge_save_item)
        .assertIsDisplayed()
        .assertIsEnabled()
        .performClick()
    compose.runOnIdle { assertEquals(1, saves) }
  }
}
