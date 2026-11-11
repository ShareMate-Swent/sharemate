# Settlement service

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

The household join batch now submits the invite code with the private profile link.
Deploy the rules in coordination with the Android update. An older client cannot add a
new membership under the tightened rules because its join batch omits this field.
