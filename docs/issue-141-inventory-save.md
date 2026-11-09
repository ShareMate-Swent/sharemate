# US-25: saving reviewed receipt items

This work implements the inventory side of [#141](https://github.com/ShareMate-Swent/sharemate/issues/141).
Buyer assignment and expense creation remain in [#142](https://github.com/ShareMate-Swent/sharemate/issues/142).

## Save and recovery contract

The receipt review passes corrected `StagedItem` rows to a focused save ViewModel.
The ViewModel depends on `ItemRepository` and `InventorySubmissionStore`, without
Firebase APIs. The existing fridge observation receives the resulting `Item`
documents; the fridge ViewModel does not acquire receipt-saving responsibilities.

A submission captures its authenticated owner, shared household and stable UUID.
Each row has a deterministic inventory document ID derived from that UUID and its
position. The repository writes the items and an immutable, owner-only submission
marker in one Firestore batch. A repeated batch cannot replace the marker, so the
server rejects the entire replay instead of overwriting edited or deleted items.
The marker is a recovery receipt, not trusted proof of a financial transaction.

Before publishing a new scan, a private local journal durably records its draft.
The draft replaces the previous active receipt only after it is safely stored;
past confirmed receipts remain discoverable in the owner's submission collection.
Drafts are editable and are not interpreted as queued writes after recovery.
Before enqueueing, the journal durably records the confirmed submission. Its
disk operations run on an injected I/O dispatcher. Firestore owns queued writes;
leaving a screen or cancelling a coroutine does not revoke them. On recreation,
the save ViewModel reloads the journal and observes the same submission rather
than assigning a new identity. Cached snapshots and local pending writes cannot
establish success. Success requires server acknowledgement; a missing marker
confirmed by the server permits retry with the retained identity and corrections.
A further scan waits for confirmation of the active submission. After restart,
cached markers alone cannot establish that confirmation.

Review ViewModels are created only after journal recovery and keyed by the
recovered identity. The description, rows and feedback scroll together while
Confirm remains fixed, including when failure feedback is long on a small screen.

Each submitted row creates a separate active, shared inventory item. Equal names
are not merged. Reviewed quantities are preserved, and absent category, expiry
date and image remain unset. The bounded batch policy is 100 rows, with names and
row identifiers limited to 1,000 characters; oversized inputs fail before writes.

## US-26 handoff

Inventory `Item` documents have no price field. The journal and submission marker
retain the reviewed rows, including nullable prices, separately. A row with
quantity 2 and `priceCents = 480` represents CHF 4.80 total; do not multiply it by
quantity again. Missing price remains unknown, not zero.

US-25 can be developed independently. Before connecting a combined final
confirmation, the owners of #141 and #142 must decide whether inventory and
expenses commit atomically or separately, including retry and recovery behavior.
An inventory success message does not assert that an expense has been recorded.

## Reviewable delivery

The formatted feature and tests exceed 2,000 changed text lines. Three PRs are
the minimum needed to keep each below the 1,000-line limit (remeasure against
its actual target before submission):

1. Submission model, repository contract/implementation, rules, interface-fake
   migrations, repository unit tests and Firestore-emulator tests.
2. Durable journal, save ViewModel, their unit tests and this integration note.
3. Receipt/staging UI, navigation wiring, string resources, pre-publication draft
   callback and Android flow tests.

To publish all three for review now, use draft PRs with the minimum dependency
chain: `feature/141-inventory-batch` targets `main`,
`feature/141-inventory-recovery` targets the batch branch, and
`feature/141-inventory-integration` targets the recovery branch. The save
ViewModel requires the first PR's repository API; the UI requires the second
PR's save state and journal. Independent bases would duplicate unmerged changes
or require temporary implementation stubs.

Merge the batch PR first, retarget recovery to updated `main`, and obtain fresh
CI and review of that head. After recovery merges, do the same for integration.
Review each PR's own diff; approval of a prerequisite is not approval of its
consumer. Do not merge dependent PRs into their temporary feature-branch bases.

Publication branches incorporate `main` at `71ee819`. The local results below
come from the earlier combined worktree, not isolated validation of each newly
published slice or the updated integrated version. CI owns that verification.

The implementation, tests and documentation are AI-assisted. Agent-run checks,
CI results and user-performed verification must be reported separately. No manual
user verification is claimed here.

The linked Figma file can currently be read through the available connection, but
the tool rejects mutations in read-only mode. Updating the receipt save-state
mockups remains pending write access.

## Agent-run verification (2026-11-08)

Local evidence is for the pre-publication combined worktree based on `1699eae`;
no user-performed manual checks, new CI result, Sonar result or merge is claimed.

- During development, `check ktfmtCheck :app:compileDebugAndroidTestKotlin`
  executed 714 JVM tests: 713 passed; the unchanged
  `FridgeItemTest.localFilePathAndFileUriAreDecodedByCoil` timed out under local
  Windows/Robolectric. Its isolated rerun also failed; assertions were preserved.
  All 22 new submission/recovery/journal JVM tests passed in that run.
- `:app:lint ktfmtCheck` passed after the review-loading recovery fix. Existing
  icon deprecation, JVM and Sonar-plugin warnings were observed.
- Both new Android test classes passed together: 8 tests covering Firestore
  atomicity, offline acknowledgement/rejection, authorization, SDK persistence
  restart, pending/retry UI, corrected rows and delayed journal recovery.
- The subsequent complete Android run passed: authoritative XML records 164
  tests, zero failures/errors and one explicitly opt-in live Commons test skipped.
  That APK predates the final compact-layout correction. The earlier obstructed
  run was interrupted because a SystemUI ANR stole window focus; the emulator
  was recovered before this successful run.
- After the compact-layout correction, affected main/Android formatting checks
  and the single `InventorySaveFlowTest#compactLandscapeKeepsPendingReviewAndRetryReachableAndShowsSuccessOnlyAfterAck`
  Android test passed. It verifies visible/enabled Confirm, invalid-to-valid
  correction, pending feedback, retry identity and server-only success.
- Independent security and code reviews found no remaining blocking finding
  after the fixes; human review remains required.

Per the user's verification policy, further local verification stays focused;
full latest-version suites, PR-specific coverage above 90% and Sonar verification
remain CI/submission gates. Figma write access and the US-26 final integration
contract remain pending as described above.
