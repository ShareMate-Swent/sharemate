<!-- Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com> -->
# Pseudo claim migration

The app reserves a normalized pseudo in `pseudoClaims/{sha256}` before setting the Firebase
display name. The Firestore rules allow owners to read their own claim, prevent clients from
listing claims, and only allow creating an unclaimed name. Existing Firebase Auth display names
and public profile names are grandfathered: the migration indexes both as `legacyUids` arrays,
including all owners of pre-existing duplicates. Their names are not changed.

Before releasing a version that uses claims:

1. Deploy the additive `firestore.rules` change.
2. From `scripts/pseudo-claims`, run `npm ci` and then run the migration in dry
   run mode with Application Default Credentials configured for the intended Firebase project.
3. Review the account, profile, named-pseudo, and duplicate-group counts. Apply the migration with
   `npm run migrate -- --apply` only after confirming the project.
4. Release the app that creates and checks claims.

The migration is idempotent and preserves live claims. If it finds a claim that conflicts with the
legacy account or profile list, it stops rather than replacing that claim. Partial batches can be
safely rerun. The script reads Firebase Auth display names and public profile names, and writes
hashed claim document IDs plus owner UIDs; it does not rename accounts or store pseudo text in the
claims collection.

Run the migration's pure tests with `npm test`. No production project credentials or data are
included in this repository; the migration must be run by an operator with the project's Admin
SDK permissions.
