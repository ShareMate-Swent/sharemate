<!-- Co-authored-by: OpenAI Codex <noreply@openai.com> -->
# Issue #17: item data layer (PR 1)

`quantity` is a positive whole-number count of units, defaulting to `1`. There was no existing
quantity/unit convention in the item or receipt models. Fractional weights and unit conversion are
outside this change. Firestore stores the count as an integer; the supported range is
`1..Int.MAX_VALUE`. `status` is the exact string `ACTIVE`, `EATEN`, or `DISCARDED`. Missing new fields
default to one unit and ACTIVE. Explicit invalid quantities/statuses cause the document to be omitted
from read flows, matching the existing treatment of invalid names/owners, without killing the flow.

Read flows still include all lifecycle states. PR 2 can filter ACTIVE items. Category now survives
Firestore reads, correcting an omission in the existing mapper. Existing positional Item constructor
arguments retain their meaning.

`updateItem(id, ItemEdit(...))` requires the four complete editable form values (name, quantity,
category, expiration date). Null category/date explicitly clears that field. Firestore `update` writes
only those four fields; status, ownership, sharing, and unknown image/receipt metadata remain intact.
`setItemStatus` updates only status and permits restoring ACTIVE. These operations never create a
missing document. `deleteItemQueued` permanently deletes and is idempotent for a missing document.

All three operations return an `ItemWrite` immediately after validation and submission to the SDK:

- `Rejected(cause)` reports invalid input or an already-known SDK failure.
- `Queued(confirmation)` means submission, not durable local application or server approval. Observe
  the usual item flows for the SDK's optimistic local state. Close the form's saving state on Queued;
  observe `confirmation` separately to report eventual success or failure. Do not block the offline
  form on that Deferred. No independent item store or network-connectivity heuristic is used.

The Android Firestore SDK enables persistent disk caching by default, and production code does not
replace or disable that setting. Writes update cached data asynchronously and synchronize when the
SDK reconnects. Server authorization may reject an optimistic local write, in which case the SDK
rolls it back and the confirmation contains the original exception. Missing documents can similarly
fail only after reconnection. Without a server, cached data cannot prove current membership or
document existence. Cancelling an await does not revoke an SDK write. Pending SDK mutations survive
process restarts; confirmation handles and their errors are process-local and cannot be recovered
through this API after restart. Emulator tests exercise network disable/enable in one process, not
process death, operating-system disk failures, or device airplane mode.

The old `addItem` and `deleteItem` methods retain their server-confirmed contracts for existing
callers; they can wait offline. PR 2 must use the queued API for editing, lifecycle and deletion.
`deleteItem` reuses queued deletion internally, then awaits confirmation to produce its Boolean.

The existing rules had no items match and therefore denied all item operations. The items match
now follows these rules (kept in sync with `firestore.rules`, which is authoritative):

- Every item belongs to an existing household (`householdId` is a required non-blank string) and
  is either `PERSONAL` (its owner only) or `SHARED` (every current member). `ItemSharing` is the
  only privacy field; there are no household-less "private" items any more, and items stored
  without a household are inaccessible.
- Access follows the household's current `memberIds`, even for the owner: a user who left cannot
  read, change, delete or create items there, though the items stay in place and come back if the
  user rejoins. Members edit and delete shared items, never the personal items of others.
- A private fridge only accepts `PERSONAL` items. `ownerId` and `householdId` are immutable, and
  only the owner changes `sharing`. Creation requires the authenticated owner as a current member.
- The security rules do not filter, so a query must prove that every result is readable.
  `ItemRepository.getFridgeItems(householdId, userId)` asks for the household's `SHARED` items
  plus the user's own (`householdId == X && (sharing == SHARED || ownerId == userId)`). A query on
  the household alone is rejected for everyone.

`FirebaseItemRepositoryEmulatorTest` uses the existing FirebaseEmulator helper and the deployed local
rules. It covers round trips, legacy defaults, partial edits and metadata retention, all statuses,
deletion, missing updates, invalid fields and identity changes, owners/members/outsiders/signed-out
users, offline cache edits/deletion followed by server synchronization, and rejection/rollback.
Admin REST reads distinguish remote data from the disabled SDK's local cache; administrative writes
are used only to seed image metadata for the partial-update test. No credentials are needed.

Run the real SDK suite with Auth/Firestore emulators on ports 9099/8080 and an Android emulator:

```text
firebase emulators:exec --only auth,firestore --project sharemate-swent "./gradlew connectedDebugAndroidTest"
```

The existing Auth UI tests additionally require `adb reverse tcp:9099 tcp:9099`, as in CI. The item
tests use `10.0.2.2` through the shared emulator helper.

Local validation for this change (Windows, Gradle JDK 17):

```text
.\gradlew.bat :app:ktfmtFormatTest :app:ktfmtFormatAndroidTest --console=plain
.\gradlew.bat :app:compileDebugAndroidTestKotlin check ktfmtCheck --console=plain
.\gradlew.bat connectedDebugAndroidTest --console=plain
```

Review validation: formatting, compilation, unit tests and lint passed: 180 unit tests, including
27 item tests, with zero failures/errors/skips. The review added tests for replacement category and
expiry values, cancellation of a confirmation wait, and direct cache assertions before and after
rejection of an unauthorized offline write.

The full instrumented task executed 48 tests on the installed API 37 emulator: 32 passed, 16 failed,
and none were skipped. All eight `FirebaseItemRepositoryEmulatorTest` tests passed against the real
Auth and Firestore emulators, including offline cache edits/deletion, synchronization and rollback,
partial-update metadata retention, and private/shared security rules. All 16 failures were existing
UI tests failing inside Espresso with `NoSuchMethodException:
android.hardware.input.InputManager.getInstance []`, before their feature assertions. The project
uses Espresso 3.6.1 and CI runs API 34; this local API 37 result does not establish CI's UI result.
API 34 and the SDK command-line package installers were not available locally. No tests were disabled
or excluded, and no unrelated dependency or workflow change was made to work around this mismatch.

The complete local instrumented report is generated at
`app/build/reports/androidTests/connected/debug/index.html`, with machine-readable results at
`app/build/outputs/androidTest-results/connected/debug/`.
