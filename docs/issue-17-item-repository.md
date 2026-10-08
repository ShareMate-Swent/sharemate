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

The existing rules had no items match and therefore denied all item operations. The new items match
grants private owners and shared-household members access. Creation requires the authenticated owner;
ordinary updates cannot modify identity, sharing or metadata and must contain valid editable values.
Other collections' rules are unchanged. Private queries now constrain both ownerId and householdId
(`null`) on the server so rules can prove private access; the old query filtered sharing only after
the server returned documents. Historically SDK-created private items already store householdId as
null. Documents that omit householdId altogether can be mapped/read by document ID, but are not
returned by Firestore's null-equality query; such external data needs householdId set to null.

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
