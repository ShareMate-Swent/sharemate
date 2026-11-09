// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
import { createHash } from "node:crypto";
import { pathToFileURL } from "node:url";

const invisible = /[\p{White_Space}\p{Cf}\p{Cc}\p{Mn}\p{Me}\p{Zl}\p{Zp}]/u;
const AUTH_USERS_PAGE_SIZE = 1000;
const FIRESTORE_BATCH_SIZE = 400;
const PROFILES_COLLECTION = "profiles";
const PSEUDO_CLAIMS_COLLECTION = "pseudoClaims";
const DISPLAY_NAME_FIELD = "displayName";

export function normalizePseudo(value) {
  const points = [...value];
  let start = 0;
  let end = points.length;
  while (start < end && invisible.test(points[start])) start++;
  while (end > start && invisible.test(points[end - 1])) end--;
  return points.slice(start, end).join("").normalize("NFKC").toLowerCase();
}

export function pseudoClaimId(value) {
  return createHash("sha256").update(normalizePseudo(value), "utf8").digest("hex");
}

export function groupLegacyPseudos(users) {
  const groups = new Map();
  for (const { uid, displayName } of users) {
    if (!uid || typeof displayName !== "string") continue;
    const normalized = normalizePseudo(displayName);
    if (!normalized) continue;
    const id = pseudoClaimId(normalized);
    const owners = groups.get(id) ?? new Set();
    owners.add(uid);
    groups.set(id, owners);
  }
  return groups;
}

async function migrate({ apply }) {
  const { applicationDefault, initializeApp } = await import("firebase-admin/app");
  const { getAuth } = await import("firebase-admin/auth");
  const { getFirestore } = await import("firebase-admin/firestore");

  initializeApp({ credential: applicationDefault() });
  const auth = getAuth();
  const firestore = getFirestore();
  const authUsers = [];
  let pageToken;
  do {
    const page = await auth.listUsers(AUTH_USERS_PAGE_SIZE, pageToken);
    authUsers.push(...page.users.map(({ uid, displayName }) => ({ uid, displayName })));
    pageToken = page.pageToken;
  } while (pageToken);

  const profiles = await firestore.collection(PROFILES_COLLECTION).get();
  const legacyNames = [
    ...authUsers,
    ...profiles.docs.map((profile) => ({
      uid: profile.id,
      displayName: profile.get(DISPLAY_NAME_FIELD),
    })),
  ];
  const groups = [...groupLegacyPseudos(legacyNames)];
  let duplicateGroups = 0;
  for (const [, owners] of groups) {
    if (owners.size > 1) duplicateGroups++;
  }
  console.log(
    `Found ${authUsers.length} accounts and ${profiles.size} profiles, ` +
      `${groups.length} named pseudos, ` +
      `${duplicateGroups} grandfathered duplicate groups.`,
  );
  if (!apply) {
    console.log("Dry run only. Re-run with --apply to write legacy claims.");
    return;
  }

  const claims = firestore.collection(PSEUDO_CLAIMS_COLLECTION);
  const entries = groups.map(([id, owners]) => [claims.doc(id), [...owners]]);
  for (let offset = 0; offset < entries.length; offset += FIRESTORE_BATCH_SIZE) {
    const chunk = entries.slice(offset, offset + FIRESTORE_BATCH_SIZE);
    const snapshots = await Promise.all(chunk.map(([reference]) => reference.get()));
    const writes = [];
    for (let index = 0; index < chunk.length; index++) {
      const [reference, owners] = chunk[index];
      const snapshot = snapshots[index];
      if (snapshot.exists) {
        const existingOwners = snapshot.get("legacyUids");
        const existingUid = snapshot.get("uid");
        const alreadyMigrated =
          Array.isArray(existingOwners) && owners.every((uid) => existingOwners.includes(uid));
        const existingOwnerIsOnlyAccount = owners.length === 1 && existingUid === owners[0];
        if (alreadyMigrated || existingOwnerIsOnlyAccount) continue;
        throw new Error(
          `Claim migration conflict at ${reference.id}; do not overwrite a live claim.`,
        );
      }
      writes.push([reference, owners]);
    }

    if (writes.length) {
      const batch = firestore.batch();
      for (const [reference, owners] of writes) batch.create(reference, { legacyUids: owners });
      await batch.commit();
    }
  }
  console.log(`Migrated ${groups.length} legacy pseudo claims.`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (process.argv.slice(2).some((argument) => argument !== "--apply")) {
    throw new Error("Usage: node migrate-pseudo-claims.mjs [--apply]");
  }
  await migrate({ apply: process.argv.includes("--apply") });
}
