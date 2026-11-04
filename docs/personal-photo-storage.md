<!-- Co-authored-by: OpenAI Codex <noreply@openai.com> -->
# Personal photo storage

This foundation is deliberately not wired to the form, gallery, camera, Commons or `Item.image`.
Construct `FirebasePersonalPhotoRepository(storage, firestore)` at the composition root
when integrating a future caller; ViewModels depend only on `PersonalPhotoRepository`.

## Contract and ownership

- `upload(itemId, source)` requires an existing Firestore item. It reads a caller-owned, stable
  file, checks JPEG/PNG signatures and a maximum of 5 MiB, then reads the confirmed item owner from
  Firestore and creates an immutable UUID object with `itemOwnerId` metadata.
- The returned `PersonalPhotoReference.path` (`itemPhotos/<itemId>/<uuid>`) is relative to the
  configured bucket. Persist that path when integrating; it works on another device using the
  same Firebase project. It carries no download token and grants no access by itself.
- `load(reference)` downloads via the authenticated SDK and returns caller-owned bytes, with
  the same size/signature checks. There is no disk cache or temporary file to clean up.
- `delete(reference)` waits for server confirmation. Missing objects are failures, not success.
- `deleteIfPresent(reference)` accepts only a confirmed missing object; other errors remain failures.
- Failures retain their exception in `Result`; cancellation is thrown. Upload/download cancellation
  calls the SDK's `cancel()`. Deletion has no cancellable SDK handle. Cancellation cannot roll back
  a server commit; an upload may leave an orphan if completion races cancellation.

## Authorization and limits

Rules read the current item and, if needed, its household (at most two Firestore documents).
The photo's immutable `itemOwnerId` must match the current item's owner. Recreating a deleted item
under a different owner cannot expose its old photos; absent/forged metadata is rejected. Objects
uploaded before this binding was introduced need administrative cleanup or trusted migration.
The item owner or an authorized household member can create/read/delete photos. Private fridges
require the creator; Storage treats a missing `type` as `SHARED` for legacy compatibility.
Item `sharing` is irrelevant to read authorization, as in `firestore.rules`. A forged user
profile is insufficient.
Listing, overwrites and metadata updates are denied. No public/bearer URL is returned or used.
Rules validate size and declared MIME type; they cannot inspect pixels. Client signature checks
are not full decoding or server-side content inspection. HEIC/WebP need future conversion.

Delete photos **before** deleting the item. After item deletion the rules deny access, including
client cleanup; administrative orphan cleanup, attachment consistency, retries, compression,
EXIF removal and clearing in-memory images at logout remain future integration responsibilities.
Leaving a household blocks subsequent authenticated SDK reads. A previously obtained tokenized
download URL can remain usable until its token is revoked; membership changes do not revoke it.
See [Firebase's download URL contract](https://firebase.google.com/docs/reference/android/com/google/firebase/storage/StorageReference#getDownloadUrl()).
Bytes already downloaded cannot be retracted.

## Local verification and remote prerequisites

CI starts Auth, Firestore and Storage. For a local Android emulator, first run
`adb reverse tcp:9099 tcp:9099`, then start those three services:
`firebase emulators:exec --only auth,firestore,storage --project sharemate-swent "./gradlew connectedDebugAndroidTest"`.
The storage tests explicitly use `10.0.2.2:9199`; unit tests use SDK doubles, never production.

`google-services.json` names `sharemate-swent.firebasestorage.app`; that declaration alone does not
prove remote provisioning. Before production use, a project administrator must verify the bucket
and Blaze billing, deploy `storage.rules`, and enable Storage-to-Firestore rules permissions when
prompted by Firebase. Also verify the bucket has no public IAM access. Nothing is deployed here.
See [Android setup](https://firebase.google.com/docs/storage/android/start) and
[cross-service rules](https://firebase.google.com/docs/storage/security/rules-conditions#enhance_with_cloud_firestore).
