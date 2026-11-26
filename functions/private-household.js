// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
const { HttpsError } = require("firebase-functions/v2/https");
const { FieldValue } = require("firebase-admin/firestore");

const { HOUSEHOLDS, PRIVATE_FRIDGE_TYPE, isId } = require("./finance-common");

const USERS = "users";
const ITEMS = "items";
const RECEIPTS = "receipts";
/** Sub-collections of a user, each document carrying the `householdId` it belongs to. */
const RECEIPT_BACKUPS = "receiptBackups";
const RECEIPT_DELETIONS = "receiptDeletions";
const HOUSEHOLD_ID_FIELD = "householdId";
const OWNER_ID_FIELD = "ownerId";
const JOIN_CODE_FIELD = "joinCode";
const REQUEST_FIELD = "householdId";
const ITEM_PHOTOS_PREFIX = "itemPhotos/";
/** Storage folders named after the household: `{prefix}{householdId}/...`. */
const HOUSEHOLD_PHOTO_PREFIXES = ["incidentPhotos/", "receiptPhotos/"];
/** Documents deleted per batch; Firestore accepts at most 500 writes in one batch. */
const PAGE_SIZE = 200;
/**
 * Cleaning passes before giving up. A pass that finds nothing ends the cleaning, so a household
 * nobody writes to needs two passes at most; more only happen when something keeps writing to it.
 */
const MAX_CLEANUP_PASSES = 5;

function parseHouseholdId(request) {
  if (!request || typeof request !== "object" || Array.isArray(request) ||
      Object.keys(request).length !== 1 || !isId(request[REQUEST_FIELD])) return null;
  return request[REQUEST_FIELD];
}

/** Only the creator may delete, and only a private household that nobody else belongs to. */
function assertDeletableBy(uid, household) {
  if (household.createdBy !== uid) {
    throw new HttpsError("permission-denied", "Only the creator can delete this household.");
  }
  const members = household.memberIds;
  if (household.type !== PRIVATE_FRIDGE_TYPE || !Array.isArray(members) ||
      members.length !== 1 || members[0] !== uid) {
    throw new HttpsError("failed-precondition", "Only a private household can be deleted.");
  }
}

/** Thrown when the household is no longer the instance that was authorized: deleted or replaced. */
class HouseholdReplaced extends Error {}

/** True when [current] is the very document [authorized] was read from, not one created since. */
function isSameInstance(current, authorized) {
  return authorized !== null && current.exists && current.createTime.isEqual(authorized.createTime);
}

/**
 * Checks before each deletion that the household is still the instance that was authorized, so a
 * household created again under the same ID is never cleaned. [ensureIn] does it inside a
 * transaction, together with the deletion it guards. [ensure] is the best that can be done before
 * a Storage deletion, which cannot be part of a transaction.
 */
function instanceGuard(householdRef, authorized) {
  const ensure = (current) => {
    if (!isSameInstance(current, authorized)) throw new HouseholdReplaced();
  };
  return {
    ensure: async () => ensure(await householdRef.get()),
    ensureIn: async (transaction) => ensure(await transaction.get(householdRef)),
  };
}

/** No check, for the data of the caller after the household is gone. */
const NO_GUARD = { ensure: async () => {}, ensureIn: async () => {} };

/**
 * Deletes the documents of [query], page by page, and returns how many it deleted. It stops after a
 * page that is not full, so documents added while it runs cannot keep it busy forever: the next
 * cleaning pass picks them up. Each page is deleted in a transaction that first runs the [guard].
 * [beforeDelete] runs on a page first, so a failure there leaves the documents in place for the
 * next attempt.
 */
async function deleteDocuments(db, query, guard, beforeDelete) {
  const paged = query.limit(PAGE_SIZE);
  let deleted = 0;
  for (;;) {
    const page = await paged.get();
    if (page.empty) return deleted;
    await guard.ensure();
    await beforeDelete(page.docs);
    await db.runTransaction(async (transaction) => {
      await guard.ensureIn(transaction);
      page.docs.forEach((doc) => transaction.delete(doc.ref));
    });
    deleted += page.docs.length;
    if (page.docs.length < PAGE_SIZE) return deleted;
  }
}

function deleteItemPhotos(bucket, items) {
  return Promise.all(items.map((item) =>
    bucket.deleteFiles({ prefix: `${ITEM_PHOTOS_PREFIX}${item.id}/` })));
}

async function deleteSubcollections(db, householdRef) {
  const collections = await householdRef.listCollections();
  for (const collection of collections) await db.recursiveDelete(collection);
}

/**
 * One pass over the content that belongs to the household: items with their photos, receipts, the
 * caller's receipt backups and deletion markers for it, and the Storage folders named after it
 * (incident and receipt photos). With [ownerId], the shared collections and the Storage folders are
 * limited to the documents and files of that user. Returns how many documents it found.
 */
async function cleanContentOnce(db, bucket, { householdId, userRef, ownerId, guard }) {
  const ofHousehold = (collection) => collection.where(HOUSEHOLD_ID_FIELD, "==", householdId);
  const ofOwner = (collection) =>
    ownerId ? ofHousehold(collection).where(OWNER_ID_FIELD, "==", ownerId) : ofHousehold(collection);
  const noPhotos = async () => {};
  let found = await deleteDocuments(db, ofOwner(db.collection(ITEMS)), guard,
    (docs) => deleteItemPhotos(bucket, docs));
  found += await deleteDocuments(db, ofOwner(db.collection(RECEIPTS)), guard, noPhotos);
  for (const name of [RECEIPT_BACKUPS, RECEIPT_DELETIONS]) {
    found += await deleteDocuments(db, ofHousehold(userRef.collection(name)), guard, noPhotos);
  }
  await guard.ensure();
  for (const prefix of HOUSEHOLD_PHOTO_PREFIXES) {
    await bucket.deleteFiles({ prefix: `${prefix}${householdId}/${ownerId ? `${ownerId}/` : ""}` });
  }
  return found;
}

/** One full pass: the content, then the sub-collections of the household document. */
async function cleanHouseholdOnce(db, bucket, householdRef, userRef, guard) {
  const found = await cleanContentOnce(db, bucket,
    { householdId: householdRef.id, userRef, ownerId: null, guard });
  await guard.ensure();
  await deleteSubcollections(db, householdRef);
  return found;
}

/**
 * Cleans the household until a pass finds nothing, so an item added from another device while an
 * earlier pass was running is removed too. This makes such a leftover very unlikely; it cannot rule
 * it out. If the household keeps receiving writes, it gives up and leaves the household in place
 * so that the caller can try again. It stops quietly, without deleting anything more, when the
 * household is deleted or replaced meanwhile.
 */
async function cleanHousehold(db, bucket, householdRef, userRef, authorized) {
  const guard = instanceGuard(householdRef, authorized);
  try {
    for (let pass = 0; pass < MAX_CLEANUP_PASSES; pass++) {
      if ((await cleanHouseholdOnce(db, bucket, householdRef, userRef, guard)) === 0) return;
    }
  } catch (error) {
    if (error instanceof HouseholdReplaced) return;
    throw error;
  }
  throw new HttpsError("aborted", "The household is still being written to. Try again.");
}

/**
 * Removes what the caller wrote to the household after the last cleaning pass and before the
 * household was deleted, such as a receipt saved from another device meanwhile. The rules refuse
 * to create any once the household is gone, so one pass is enough. Only the caller's own data is
 * touched, which makes it safe even when the household was already gone, so a retry runs it again.
 */
function sweepOwnData(db, bucket, userRef, householdId) {
  return cleanContentOnce(db, bucket,
    { householdId, userRef, ownerId: userRef.id, guard: NO_GUARD });
}

/**
 * Removes the household document and unlinks the caller's profile in one transaction.
 *
 * The household is read again inside the transaction and only deleted when it is the same instance
 * that was authorized and cleaned ([authorized]: its snapshot, or null when it was missing). One
 * that appeared or was replaced since is left alone, and so is the profile link that may belong to
 * it. A household that is gone is not an error. The profile is only unlinked while it still points
 * at this household. Returns whether the household is gone at the end.
 */
function removeHouseholdAndLink(db, householdRef, userRef, authorized) {
  return db.runTransaction(async (transaction) => {
    const household = await transaction.get(householdRef);
    const user = await transaction.get(userRef);
    if (household.exists) {
      if (!isSameInstance(household, authorized)) return false;
      transaction.delete(householdRef);
    }
    if (user.exists && user.get(HOUSEHOLD_ID_FIELD) === householdRef.id) {
      transaction.set(userRef, {
        [HOUSEHOLD_ID_FIELD]: FieldValue.delete(),
        [JOIN_CODE_FIELD]: FieldValue.delete(),
      }, { merge: true });
    }
    return true;
  });
}

/**
 * Deletes the caller's private household with everything in it: its items and their photos, its
 * receipts and receipt backups, its sub-collections and any incident or receipt photos. It cleans
 * until a pass finds nothing, and every deletion checks that the household is still the instance
 * that was authorized. The household document goes last, in the same transaction that unlinks the
 * caller's profile, so a failed attempt leaves the household in place and calling again only redoes
 * what is left. Once it is gone, a last sweep removes what the caller wrote meanwhile. A household
 * that no longer exists counts as already deleted, so a retry after a lost response succeeds and
 * runs the sweep again.
 *
 * @param db Firestore with admin rights.
 * @param bucket Default Storage bucket with admin rights.
 * @param uid Authenticated caller, or undefined.
 * @param request Wire payload: exactly `{ householdId }`.
 */
async function deletePrivateHousehold(db, bucket, uid, request) {
  if (!uid) throw new HttpsError("unauthenticated", "Sign in to delete a household.");
  const householdId = parseHouseholdId(request);
  if (!householdId) throw new HttpsError("invalid-argument", "Invalid household.");
  const householdRef = db.collection(HOUSEHOLDS).doc(householdId);
  const userRef = db.collection(USERS).doc(uid);
  const snapshot = await householdRef.get();
  let authorized = null;
  if (snapshot.exists) {
    assertDeletableBy(uid, snapshot.data());
    authorized = snapshot;
    await cleanHousehold(db, bucket, householdRef, userRef, authorized);
  }
  if (await removeHouseholdAndLink(db, householdRef, userRef, authorized)) {
    await sweepOwnData(db, bucket, userRef, householdId);
  }
  return { [REQUEST_FIELD]: householdId };
}

module.exports = { deletePrivateHousehold, PAGE_SIZE, MAX_CLEANUP_PASSES };
