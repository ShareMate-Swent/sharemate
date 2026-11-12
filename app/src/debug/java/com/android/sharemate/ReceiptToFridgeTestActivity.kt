package com.android.sharemate

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

/** Delivers the supplied fixture through Android's gallery result contract. */
class ReceiptToFridgeTestActivity : ComponentActivity() {
  var galleryImage: Uri? = null

  override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
    val pickerAction =
        ActivityResultContracts.PickVisualMedia()
            .createIntent(
                this, PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            .action
    val image = galleryImage
    if (image != null && intent.action == pickerAction) {
      galleryImage = null
      window.decorView.post {
        activityResultRegistry.dispatchResult(
            requestCode, Activity.RESULT_OK, Intent().setData(image))
      }
      return
    }
    super.startActivityForResult(intent, requestCode, options)
  }
}
