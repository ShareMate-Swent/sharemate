<!-- Co-authored-by: OpenAI Codex <noreply@openai.com> -->
# Financial services

Run the backend tests from the repository root with Node.js 22 and Firebase CLI installed.

```sh
npm --prefix functions install
firebase emulators:exec --only auth,firestore,storage,functions --project demo-sharemate "npm --prefix functions test"
```

The callable runs in `europe-west6`. Create the Android adapter with a Firebase Functions
instance for that region. A confirmed response means the transaction committed. An
interrupted request has an uncertain outcome, so retain the original operation ID and
payload when retrying.

The transaction reads the shared household's trusted expense records and settlement history.
Expenses live at `households/{id}/expenses/{expenseId}` with explicit shares, and must be
published by a trusted writer. Settlements live at `households/{id}/settlements/{operationId}`.
The private settlement revision document serializes competing writes. Ledger validation
scans the household's history on a new payment, so read cost grows with that history.

Only a current household member can record their own reimbursement. The recipient can be a
former member if the household ledger still shows an outstanding debt to them. Leaving does
not erase the debt or restore a former member's access to the household ledger.

The Functions endpoint and settlement rules require deployment before an app can use them.

`recordExpense` in the same region creates immutable expenses through authenticated household
membership validation, with stable retry IDs and exact integer-cent shares. Direct client expense
writes remain denied. See [the submission contract](../docs/purchase-expense-submission.md) for
authorization, replay and proposed inventory/expense recovery semantics. Backend unit tests can
run independently with `node --test functions/expenses.unit.test.js` from the repository root;
the full test command above also runs callable/Firestore emulator scenarios.

The household join batch now submits the invite code with the private profile link.
Deploy the rules in coordination with the Android update. An older client cannot add a
new membership under the tightened rules because its join batch omits this field.

`cleanupDeletedHouseholdReceipts` runs only after a household document is deleted. It removes
the household's `receiptBackups`, legacy `receipts`, and `receiptPhotos/{householdId}/` files.
Leaving a shared household does not trigger this function and keeps its history available to
the remaining members. Cleanup is asynchronous and retries on failure; membership rules revoke
access immediately. Deploy the new function alongside the Android update. This change does not
alter whether the household feature permits leaving a private household.

The Android client clears its receipt cache and photos after a successful departure. Before
leaving a shared household it finishes uploading its own retained receipts; an upload failure
keeps membership and local history intact. Server membership snapshots also reconcile local
caches after a departure or deletion on another device. Offline snapshots never trigger eviction.
Rejoining downloads the household history again through the existing receipt synchronization.

Receipt backups are scoped by their immutable creator at
`users/{ownerId}/receiptBackups/{receiptId}`. Deploy `firestore.indexes.json` with the rules and
updated clients; household history uses a `receiptBackups` collection-group query. Existing
preview backups at the old top-level path must be migrated by a trusted administrator before
switching clients; never accept a caller-supplied owner as a migration authority.

Corrections send `originalTitle` alongside the original rows and expense. The server compares
all three before applying a new correction. The original title must be retained in the local
correction journal so retries preserve the same request identity. Older clients that omit it
must refresh/update rather than overwrite a newer title.

## Private household deletion

`deletePrivateHousehold` in the same region deletes a private household with everything in it: its
items and their photos, its receipts, the creator's receipt backups and deletion markers for it,
its sub-collections, incident and receipt photos, and finally the household document together with
the link on the creator's profile. Only the creator can call it, only for a private household with
no other member, so a shared household is never deleted this way. Clients cannot delete a household
directly; the Firestore rules deny it. The request is exactly `{ householdId }`.

The household document goes last, so a failed call leaves the household in place and calling again
only redoes what is left. The cleaning repeats until a pass finds nothing, so an item added from
another device meanwhile is removed too, and a household that keeps receiving writes is kept and the
call fails, to be repeated. Every deletion checks, in the same transaction, that the household is
still the instance that was authorized (same `createTime`). If it was deleted or created again under
the same ID, the cleaning stops and the new household is not touched. The final transaction does the
same check.

Once the household is gone, a last sweep removes what the caller wrote meanwhile (for example a
receipt saved from another device after the last pass): their items, receipts, receipt backups and
deletion markers, and their photos in the household folders. The rules refuse to create any once the
household is gone, so one pass is enough. Only the caller's own data is swept, which keeps it safe
when the household was already gone, so a retry runs it again. This makes a leftover very unlikely
but cannot rule it out; for example, a write that lands in the same instant as the final transaction
can still survive. A household that is already gone counts as deleted. The function runs for up to
300 seconds, so callers should wait longer than that.

The function and its Storage access need deployment. Its tests run in two ways: the unit tests need
no emulator, and `private-household.test.js` calls the function against the Auth, Firestore, Storage
and Functions emulators.

```sh
node --test functions/private-household.unit.test.js
firebase emulators:exec --only auth,firestore,storage,functions --project demo-sharemate-ci \
  "node --test functions/private-household.test.js"
```

## Recipe service (#338)

`suggestRecipes` is an authenticated v2 callable in `europe-west6`. It checks current
household membership and accepts only `{ householdId, ingredients }`: 1–50 names,
each at most 80 characters. Android reuses `RecipeIngredientProvider`; empty stock
returns an empty list locally. Availability and expiry rules are not duplicated.

The callable sends at most 10 results through Spoonacular `findByIngredients`,
with an 8-second provider timeout inside the 15-second function timeout. It returns
only `{ recipes: [{ id, title, imageUrl, matchingIngredientCount,
missingIngredientCount }] }`. Images may be null. Provider diagnostics and credentials
are never returned or logged. Quota errors use `resource-exhausted`, provider timeout
uses `deadline-exceeded`, and other provider failures use safe `internal` errors.
Android maps transport unavailability to `Offline`, other failures to `ServiceError`,
and always preserves coroutine cancellation. Cancellation does not guarantee that an
already executing backend request stops or avoids consuming provider quota.

Deployment requires an authorized maintainer to create `SPOONACULAR_API_KEY` using
Firebase Secret Manager and deploy `suggestRecipes`. This change does not configure
any real secret or deploy anything. Never put the key in Android resources, BuildConfig,
source control, test fixtures or CI. Missing configuration fails safely. Configure
provider attribution according to the selected Spoonacular plan before release;
quota is shared across the project. Server-side per-UID limits are described below.
No persistent recipe cache, instructions, new matching algorithm or expiry ranking
is included. Ingredient names are forwarded without translation.

Deterministic tests need no Spoonacular account or real key:

```sh
node --test functions/recipes.unit.test.js
# With local Auth and Firestore emulators (demo project only):
node --test functions/recipes.test.js
```

The unit tests inject HTTP transport. The integration test verifies emulator-issued
identity tokens and current Firestore membership against the service handler, using
injected HTTP responses; it does not invoke a deployed function or Secret Manager.
The empty-fridge Android navigation regression also needs no provider key.

AI assistance: OpenAI Codex generated the service adapter, callable, tests and this
section. Automated results are recorded in the delivery report; no personal human
verification or manual emulator test is asserted here.

### Server-side recipe request limits

Before calling Spoonacular, the service reserves usage atomically in Firestore:

- Maximum 10 accepted requests per Firebase Auth UID per UTC calendar day.
- At least 30 seconds between accepted requests, including across midnight.
- The UID comes from callable authentication, never from request data; changing
  household or ingredients does not reset the budget. Different UIDs are independent.
- Each transaction retry re-reads the trusted server clock. A new UTC day resets the
  count, but preserves the previous timestamp for the cooldown; clock rollback cannot
  reopen a previous allowance.
- Authentication, input validation, membership and service-key configuration are
  checked before reservation. Exceeding a limit returns a safe `resource-exhausted`.
  A failed/corrupt Firestore reservation returns a safe `internal` and never invokes
  Spoonacular. Provider failures and timeouts do not refund a committed reservation.

`recipeRequestBudgets/{sha256(uid)}` stores only the UTC day, accepted count and last
accepted server time in milliseconds. The existing Firestore rules grant no client
access to this root collection. Emulator tests assert that owners, other users and
unauthenticated clients cannot read, list, create, update or delete these records.
Only the Admin SDK writes them. The limits do not alter the `suggestRecipes` request
or success-response contract. Android already maps `RESOURCE_EXHAUSTED` to ServiceError.

Tests require no real API key. Run all `*.unit.test.js` files for policy and failure
cases, and `recipe-rate-limit.test.js` with local Auth/Firestore emulators for real
transaction contention, UTC rollover, UID isolation and client-rule enforcement.
Per-UID protection does not impose an overall project budget across multiple accounts.
