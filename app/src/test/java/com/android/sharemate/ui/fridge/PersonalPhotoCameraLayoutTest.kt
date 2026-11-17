// Co-authored-by: OpenAI Codex <noreply@openai.com>
package com.android.sharemate.ui.fridge

import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.view.descendants
import com.android.sharemate.ui.barcode.BarcodeCamera
import com.android.sharemate.ui.barcode.BarcodePhotoTestTags
import com.google.common.util.concurrent.SettableFuture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class PersonalPhotoCameraLayoutTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun cameraPreviewClipsItsNativeContentBetweenHeaderAndControls() {
    lateinit var root: View
    val provider = SettableFuture.create<ProcessCameraProvider>()
    compose.setContent {
      root = LocalView.current.rootView
      MaterialTheme {
        Column(Modifier.verticalScroll(rememberScrollState())) {
          Text("Photo", Modifier.testTag("photo_header"))
          BarcodeCamera(
              true, {}, { fail("Unexpected camera error") }, providerLoader = { provider })
        }
      }
    }
    compose.runOnIdle {
      val preview = (root as ViewGroup).descendants.filterIsInstance<PreviewView>().single()
      assertTrue(preview.clipToOutline)
      assertSame(ViewOutlineProvider.BOUNDS, preview.outlineProvider)
    }
    val viewfinder = compose.onNodeWithTag(BarcodePhotoTestTags.VIEWFINDER)
    viewfinder.assertHeightIsEqualTo(300.dp)
    val bounds = viewfinder.fetchSemanticsNode().boundsInRoot
    val header = compose.onNodeWithTag("photo_header").fetchSemanticsNode().boundsInRoot
    val capture =
        compose.onNodeWithTag(BarcodePhotoTestTags.CAPTURE).fetchSemanticsNode().boundsInRoot
    assertTrue(bounds.top >= header.bottom)
    assertTrue(bounds.bottom <= capture.top)
  }
}
