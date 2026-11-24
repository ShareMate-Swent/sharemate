<!-- Co-authored-by: OpenAI Codex <noreply@openai.com> -->
# Financial services

Run the backend tests from the repository root with Node.js 22 and Firebase CLI installed.

```sh
npm --prefix functions install
firebase emulators:exec --only auth,firestore,functions --project demo-sharemate "npm --prefix functions test"
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

`deletePrivateHousehold` in the same region deletes a private household with everything in it:
its items and their photos, its receipts, the creator's receipt backups and deletion markers for
it, its sub-collections, incident and receipt photos, and finally the household document together
with the link on the creator's profile. Only the creator can call it,
only for a private household with no other member, so a shared household is never deleted this way.
Clients cannot delete a household directly; the Firestore rules deny it.

The household document goes last, so a failed call leaves the household in place and calling again
only redoes what is left. The cleaning repeats until a pass finds nothing, so an item added from
another device meanwhile is removed too; this makes a leftover very unlikely but cannot rule it out,
and a household that keeps receiving writes is kept and the call fails, to be repeated. The final
transaction reads the household again and only deletes the instance that was checked. A household
that is already gone counts as deleted. The Android adapter sends `{ householdId }` and treats any
error as "not deleted", with no offline queue. The function and its Storage access need deployment
before the app can use it, and its tests run in two ways: the unit tests need no emulator, and
`private-household.test.js` calls the function against the Auth, Firestore, Storage and Functions
emulators.

```sh
node --test functions/private-household.unit.test.js
firebase emulators:exec --only auth,firestore,storage,functions --project demo-sharemate-ci \
  "node --test functions/private-household.test.js"
```
