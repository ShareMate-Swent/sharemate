# Purchase review handoff (#369 / #370)

The staging UI remains inventory-only by default. Task #370's purchase
coordinator should construct StagingViewModel with
PurchaseReview.forUser(currentUserId, currentHouseholdMemberNames).
Use the existing authentication and household/name repository abstractions
in that coordinator. Do not fetch Firebase data in StagingScreen.

Forward current membership snapshots to updatePurchaseMembers. Recreate the
review when the active household or signed-in account changes; do not reuse
a purchase across household scopes. Member removal invalidates the current
review until the user explicitly reselects a payer/participant.

Pass onConfirmPurchase to receive ReviewedPurchase (reviewed rows, payer,
total cents, shares). The existing onConfirm callback remains inventory-only.
This PR deliberately performs no expense or inventory persistence.
The #370 coordinator must revalidate the current scope and persist the
reviewed payload/operation ID for its offline/retry flow.

Participants start empty. Equal splitting uses row totals once (quantity is
not a multiplier), with remainder cents assigned by lexicographic UID order.
The payer may be excluded. Zero-cent participant shares are supported by
recordExpense. The current backend allows at most 100,000,000 cents and
requires the authenticated submitter to be the payer; choosing another payer
in review does not grant permission to submit on their behalf.
