// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfilePhotoSourcesTest {
  private fun supportedPhotoUrl(url: String?) = supportedPhotoUrl(url, "alex", BUCKET)

  @Test
  fun googleAccountPhotosOverHttpsAreKept() {
    for (url in
        listOf(
            "https://lh3.googleusercontent.com/a/ACg8ocK=s96-c",
            "https://lh5.googleusercontent.com/photo.jpg",
            "HTTPS://LH3.GOOGLEUSERCONTENT.COM/a/photo",
            // The member's own upload in our bucket.
            "$STORAGE/b/$BUCKET/o/users%2Falex%2Favatars%2Fp1?alt=media&token=t")) {
      assertEquals(url, supportedPhotoUrl(url))
    }
  }

  @Test
  fun otherSourcesAreReplacedByThePlaceholder() {
    for (url in
        listOf(
            // Any server a member controls.
            "https://tracker.example/pixel.png",
            // Not encrypted.
            "http://lh3.googleusercontent.com/a/photo",
            // Look-alike hosts.
            "https://lh3.googleusercontent.com.evil.example/a/photo",
            "https://evil-lh3.googleusercontent.com/a/photo",
            "https://googleusercontent.com/a/photo",
            // The real host is evil.example; the Google part is only a user name.
            "https://lh3.googleusercontent.com@evil.example/a/photo",
            // Storage objects that are not this member's profile photo in our bucket.
            "$STORAGE/b/$BUCKET/o/photo.jpg",
            "$STORAGE/b/other-bucket/o/users%2Falex%2Favatars%2Fp1",
            "$STORAGE/b/$BUCKET/o/users%2Fsam%2Favatars%2Fp1",
            "$STORAGE/b/$BUCKET/o/users%2Falex%2Favatars%2Fnested%2Fp1",
            "$STORAGE/b/$BUCKET/o/users%2Falex%2Fother%2Fp1",
            "http://firebasestorage.googleapis.com/v0/b/$BUCKET/o/users%2Falex%2Favatars%2Fp1",
            "https://evil.example/v0/b/$BUCKET/o/users%2Falex%2Favatars%2Fp1",
            "file:///sdcard/photo.jpg",
            "not a url",
            "https://bad host/photo",
            "")) {
      assertNull(url, supportedPhotoUrl(url))
    }
    assertNull(supportedPhotoUrl(null))
  }

  @Test
  fun uploadsAreRejectedWithoutABucket() {
    val upload = "$STORAGE/b/$BUCKET/o/users%2Falex%2Favatars%2Fp1"
    assertNull(supportedPhotoUrl(upload, "alex", photoBucket = null))
  }

  private companion object {
    const val BUCKET = "sharemate.appspot.com"
    const val STORAGE = "https://firebasestorage.googleapis.com/v0"
  }
}
