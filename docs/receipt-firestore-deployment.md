<!-- Co-authored-by: OpenAI Codex <noreply@openai.com> -->
# Deploy receipt Firestore configuration

Merging Android code does not deploy Firebase configuration. Deploy the reviewed
`firestore.rules` and `firestore.indexes.json` from `main` together before testing
receipt saves against `sharemate-swent`.

The receipt feature needs:

- `inventorySubmissions` creation for both PRIVATE and SHARED households, while
  retaining authenticated ownership and membership checks.
- Rules for `users/{owner}/receiptBackups/{receipt}` and collection-group reads;
  rules for the old root-level `receiptBackups` collection are insufficient.
- Both composite `receiptBackups` indexes and the `householdId` collection-group
  field index declared in `firestore.indexes.json`.

## Deployment

Use a clean checkout of the reviewed `main` commit and a Firebase account with
deployment access. Record the commit and existing Firebase rules release before
deploying so the previous configuration can be restored if needed.

```powershell
git status --short
git rev-parse HEAD
firebase projects:list
firebase deploy --project sharemate-swent --only firestore:rules,firestore:indexes
firebase firestore:indexes --project sharemate-swent
```

This command deploys only Firestore rules and indexes. It does not deploy Cloud
Functions, Storage rules or the Android application. Never deploy permissive
temporary rules to bypass a receipt error.

Index creation is asynchronous: wait for the indexes to become **Enabled** in the
Firestore console before checking receipt history. CLI listing alone does not
prove that the indexes have finished building.

## Device verification

1. Restart the app (permanent failed receipt listeners stop retrying until recreated).
2. In a private household, retry a retained receipt and check its items in the fridge.
3. In a shared household, save a receipt and verify it from a second member account.
4. Confirm that another member cannot read a private draft or access another household.
5. Queue a receipt offline, reconnect, and wait for server confirmation.

Do not clear app data or uninstall while receipts are only retained locally.
If a save still fails, record its Firebase error code and APK commit. A failed
history listener does not by itself prove that the inventory write failed.

## Deployment record

Before this change, inspection found Firestore rules last updated on 2026-11-23
and no deployed receipt indexes. The reviewed configuration is already present
in `main`; this PR documents the missing deployment step rather than weakening
access checks. Deployment and device verification results must be recorded
separately; merging this document does not execute the command.

On 2026-11-26, the Firestore configuration from `main` commit `bb2bc894` was
deployed to `sharemate-swent` with the targeted command above. Firebase accepted
the rules (one existing nullable-string warning) and both composite indexes;
the indexes were still `CREATING` immediately after deployment. Device checks
have not yet been performed. The previous ruleset was
`290fa6b1-3adf-496b-b9f9-79cb2dd60ef6`.
