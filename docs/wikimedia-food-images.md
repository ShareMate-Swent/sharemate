<!-- Co-authored-by: OpenAI Codex <noreply@openai.com> -->
# Wikimedia Commons food images

`MainActivity.createFoodImageSelector` now uses `CommonsFoodImageRepository`, behind the existing
`FoodImageRepository` interface. No account or API key is needed. The former Openverse implementation
and its provider-specific tests have been removed; there is no fallback to Openverse.

After validation, `FridgeViewModel` saves the item with a placeholder and closes the form as soon as
creation succeeds. It then searches for an image in a separate job and attaches the result through
`ItemRepository.updateItemImageIfAbsent`. The repository atomically ignores deleted or renamed items
and items that already have an image. Inventory flows supply the updated image and credits to the UI.

Lookup jobs belong to the retained fridge ViewModel: switching tabs preserves them, while leaving
the session or household cancels them and their network calls. No work resumes after process death.
Temporary failures receive at most three attempts, each bounded by the existing 20-second timeout,
with two-second pauses. Failed lookups, timeouts and failed image writes keep the saved placeholder.
They never reopen the form or repeat creation. Old placeholder items do not automatically retry.

## Manual verification

1. Run the app and create a new item named `tacos`. The form should close after the save, with a
   placeholder while lookup is pending.
2. Switch tabs and return. Lookup should continue, and a successful result should display an image
   with Commons attribution. A provider failure can leave the placeholder.
3. Restart the app after the image arrives; the image and credits should still be present.
4. Delete or rename an item before lookup completes. It must not be recreated or receive an image
   based on an obsolete name. Existing images must remain unchanged.
5. Sign out during lookup; no task from that session should continue applying images.

## Search and attribution

- Use the MediaWiki Action API on `commons.wikimedia.org`, with a descriptive project User-Agent.
  The same User-Agent is required on thumbnail requests: the default OkHttp identification was
  observed to receive HTTP 403, while ShareMate's identification received HTTP 200 for the same URL.
- Search file titles, with an English food context to reduce non-food matches such as English "pain".
  User text stays inside a quoted literal. Select the first usable result by the generator's relevance
  index, rather than the JSON array order.
- Request at most ten results and a 640-pixel thumbnail. Store its HTTPS reference, not the original
  image bytes. JPEG, PNG and WebP thumbnails are supported by the existing image loader.
- Accept CC0, CC BY and CC BY-SA with a matching Creative Commons license URL. Keep the source page,
  title, author, author link when available, and license/version. Attributed licenses require an author.
  Decode Commons' HTML metadata to text; never render it as HTML in the UI.
- Preserve the existing HTTP limits and cancel the underlying OkHttp call when its coroutine is
  cancelled. MediaWiki errors inside HTTP 200 responses are failures, not empty search results.
- Image relevance is a suggestion, not recognition. Ambiguous names, French wording, the license
  filter and Commons overload can reduce coverage. There is no translation service or uptime promise.

Sources: [Commons API](https://commons.wikimedia.org/wiki/Commons:API),
[Imageinfo](https://www.mediawiki.org/wiki/API:Imageinfo),
[API etiquette](https://www.mediawiki.org/wiki/API:Etiquette),
[reuse requirements](https://commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia).

## Verification

Unit tests use a local HTTP server. They cover request encoding, ranking, thumbnails, attribution,
licenses, malformed responses, API/HTTP/network errors, cancellation and deadlines. UI tests check
Commons attribution and CC BY-SA without contacting an external image service.

With the Auth and Firestore emulators running, `CommonsImageFirestoreTest` checks creation through the
ViewModel and rereading the stored image and credits using the server and a fresh repository. Its
normal test uses a controlled response, so CI does not depend on Commons. Both tests gate lookup
until creation is complete and the placeholder is confirmed by a server read, then verify the later
image through the inventory flow and a fresh Firestore read. Selector, ViewModel and navigation tests
also cover failures, retry cancellation, tab changes and session teardown without external services.

An opt-in test additionally contacts the real Commons API for "tacos", saves/rereads the result in the
Firestore emulator, and decodes the returned thumbnail with Coil. Run this explicitly, with an Android
emulator and the Firebase CLI available:

```sh
adb reverse tcp:9099 tcp:9099
firebase emulators:exec --only auth,firestore --project sharemate-swent \
  './gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.android.sharemate.model.image.CommonsImageFirestoreTest -Pandroid.testInstrumentationRunnerArguments.commonsLive=true --console=plain'
```

Manual API observations on 27 October 2026: the contextual title query returned compatible images for
tacos, pomme, banane, tomate, carotte, riz and soupe à l'oignon, generally in 2–4 seconds. "Pain"
returned a temporary search-overload error on that run. Some results show prepared dishes containing
the named ingredient; these observations do not establish exact relevance or long-term availability.

Validation executed on 27 October 2026:

- `./gradlew check ktfmtCheck --console=plain`: passed; 364 unit tests, lint and formatting.
- The opt-in command above: both tests passed, including a real Commons search, Firestore server
  reread, fresh repository flow, and thumbnail decoding through the same request builder as the UI.
- Full `connectedDebugAndroidTest` with Auth/Firestore emulators on the installed API 37 emulator:
  89 tests, 60 passed, 28 failed, one opt-in test skipped. All failures contain
  `NoSuchMethodException: android.hardware.input.InputManager.getInstance` from Espresso. The
  deterministic Commons persistence test passed in this run. Full UI validation on CI's API 34
  remains unverified locally: its system image is not installed.

Openverse removal validated on 28 October 2026:

- `./gradlew check ktfmtCheck --console=plain`: passed; 342 unit tests, lint and formatting.
  The 22 deleted tests covered only the removed Openverse provider; all 15 Commons HTTP tests and
  the provider-independent selection tests still pass.
- Full Android run on API 37: 89 tests, 56 passed, 32 failed and one opt-in test skipped. Of the
  failures, 28 are the Espresso compatibility error above. Four authentication tests failed because
  their required `adb reverse tcp:9099 tcp:9099` forwarding was missing.
- After restoring forwarding, all six targeted tests passed: the four authentication tests and
  both Commons/Firestore tests, including the live image lookup, server reread and thumbnail decode.
  An earlier targeted attempt was interrupted by an ADB connection loss; the final run completed.
  The full suite was not rerun after restoring forwarding; API 34 and the updated SonarCloud
  duplication measurement still require CI validation.


Background-image integration validated on 29 October 2026, based on main at `649a057`:

- `./gradlew check ktfmtCheck assembleDebug --console=plain`: passed; 412 unit tests, lint,
  formatting and the debug APK build.
- Targeted `connectedDebugAndroidTest` with Auth/Firestore emulators and `commonsLive=true`:
  all 16 tests passed, none skipped. Classes: `CommonsImageFirestoreTest`,
  `FridgeImageFirestoreTest` and `FirebaseItemRepositoryEmulatorTest`.
- This verifies server-confirmed placeholder creation before releasing lookup, later image arrival,
  actual Commons thumbnail decoding and Firestore reread, plus the atomic update safeguards.
- The complete instrumented UI suite and manual gestures were not rerun for this integration.
  The previously documented API 37/Espresso limitation remains; API 34 validation requires CI.
