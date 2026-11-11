// Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
package com.android.sharemate.model.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfilePhotoSourcesTest {
  @Test
  fun googleAccountPhotosOverHttpsAreKept() {
    for (url in
        listOf(
            "https://lh3.googleusercontent.com/a/ACg8ocK=s96-c",
            "https://lh5.googleusercontent.com/photo.jpg",
            "HTTPS://LH3.GOOGLEUSERCONTENT.COM/a/photo")) {
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
            // Not supported until profile photos are uploaded to Storage (US-32).
            "https://firebasestorage.googleapis.com/v0/b/bucket/o/photo.jpg",
            "file:///sdcard/photo.jpg",
            "not a url",
            "https://bad host/photo",
            "")) {
      assertNull(url, supportedPhotoUrl(url))
    }
    assertNull(supportedPhotoUrl(null))
  }
}
