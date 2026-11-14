<!-- Co-authored-by: OpenAI Codex <noreply@openai.com> -->
# Expense submission contract

Task #368 supplies an expense-only producer. It does not enable purchase UI or replace
inventory submission, the ledger observer, balance calculation or settlement.

## Wire and authorization

`recordExpense` runs in `europe-west6`. The Android adapter implements
`ExpenseSubmissionRepository` and receives an authenticated Firebase Functions instance.
The payload has exactly the existing `SharedExpense` fields: `id`, `householdId`,
`paidBy`, `totalAmountCents`, `shares`. Total and share amounts cross the callable boundary
as canonical unsigned decimal strings, then become Firestore signed 64-bit integers.
Total is positive; shares are nonnegative, nonempty and sum exactly to the total.
No floating-point conversion or automatic allocation is performed by this endpoint.

The authenticated submitter, selected payer and participants must be current members of
the selected shared household on first creation. The submitter may select another member
as payer. This records a member's declared purchase; it is not proof of an external payment.
The payer need not consume a share. Direct client writes to expenses remain denied.

The expense is created at `households/{householdId}/expenses/{id}` with no extra fields,
preserving compatibility with strict ledger parsing. A server-only marker at
`users/{authenticatedUid}/expenseSubmissions/{id}` binds the submitter's operation to that
household. Expense, marker and the existing settlement contention document commit in one
transaction. This serializes new expenses against concurrent settlements.

An identical retry by the same current submitter returns the original payload. Conflicting
reuse of an expense ID or reuse of that submitter's operation in another household is refused.
Changing the ID intentionally creates a different expense: the caller must retain its ID.
Historical payer/participant departures do not invalidate a committed expense's replay,
but a revoked submitter cannot retry or read the ledger.

## Proposed integration for #370

Inventory and expense completion are separate. Reuse the existing inventory submission UUID
as the expense ID, retain the immutable reviewed payload before submission, and finish
inventory confirmation before requesting expense creation. Do not submit inventory again
after its confirmation. A later expense failure leaves inventory saved and finances pending
or rejected; it never means the purchase fully succeeded and does not roll inventory back.

Extend the existing durable journal to retain payer, shares and separate progress. A locally
queued command is owned by that journal, not by this callable adapter. Offline confirmation
must persist it without waiting for a server; reconnect resumes the original operation.
Return from `recordExpense` confirms server commit. Transport failure, cancellation or an
unexpected response is an unknown outcome and must retain the original ID/payload for retry.
`ExpenseRejectedException` conclusively rejects this attempt only; a previous attempt may
already have committed. Reconcile conflicts rather than creating a replacement expense.
Keep recovery scoped to the original authenticated account and household. Older inventory-only
journal entries must not silently acquire an expense during migration.

This is the implementation's proposed handoff, not a claim of agreement with #141/#142 owners.
Coordinate ownership and confirm the handoff before #370 enables financial confirmation.
Equal splitting/rounding belongs to #369; reviewed row totals must not be multiplied again.
Missing prices need correction before financial confirmation. Receipt history remains #300.

## Deployment and verification

Deploy the new callable before wiring it into the app. No client expense-write permission is
introduced. `functions/expenses.test.js` exercises real callable authentication, Firestore
transactions, authorization, retry conflicts and unchanged expense-write rules in the emulators.
The existing `npm --prefix functions test` CI command discovers these tests automatically.
The adapter JVM tests cover exact amounts, acknowledgements, retries and failure classification.
