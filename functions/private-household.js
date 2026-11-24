// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
const { HttpsError } = require("firebase-functions/v2/https");
const { FieldValue } = require("firebase-admin/firestore");

const { HOUSEHOLDS, PRIVATE_FRIDGE_TYPE, isId } = require("./finance-common");

const USERS = "users";
const ITEMS = "items";
const RECEIPTS = "receipts";
const HOUSEHOLD_ID_FIELD = "householdId";
const JOIN_CODE_FIELD = "joinCode";
const REQUEST_FIELD = "householdId";
const ITEM_PHOTOS_PREFIX = "itemPhotos/";
const INCIDENT_PHOTOS_PREFIX = "incidentPhotos/";
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

/**
 * Deletes the documents of [collection] that belong to the household, page by page, and returns
 * how many it deleted. It stops after a page that is not full, so documents added while it runs
 * cannot keep it busy forever: the next cleaning pass picks them up. [beforeDelete] runs on a page
 * first, so a failure there leaves the documents in place for the next attempt.
 */
async function deleteHouseholdDocuments(db, collection, householdId, beforeDelete) {
  const query = db.collection(collection).where(HOUSEHOLD_ID_FIELD, "==", householdId).limit(PAGE_SIZE);
  let deleted = 0;
  for (;;) {
    const page = await query.get();
    if (page.empty) return deleted;
    await beforeDelete(page.docs);
    const batch = db.batch();
    page.docs.forEach((doc) => batch.delete(doc.ref));
    await batch.commit();
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
 * One pass over everything that belongs to the household: items with their photos, receipts,
 * sub-collections and incident photos. Returns how many items and receipts it found.
 */
async function cleanHouseholdOnce(db, bucket, householdRef) {
  const householdId = householdRef.id;
  const items = await deleteHouseholdDocuments(db, ITEMS, householdId, (docs) => deleteItemPhotos(bucket, docs));
  const receipts = await deleteHouseholdDocuments(db, RECEIPTS, householdId, async () => {});
  await deleteSubcollections(db, householdRef);
  await bucket.deleteFiles({ prefix: `${INCIDENT_PHOTOS_PREFIX}${householdId}/` });
  return items + receipts;
}

/**
 * Cleans the household until a pass finds nothing, so an item added from another device while an
 * earlier pass was running is removed too. This makes such a leftover very unlikely; it cannot rule
 * it out. If the household keeps receiving writes, it gives up and leaves the household in place
 * so that the caller can try again.
 */
async function cleanHousehold(db, bucket, householdRef) {
  for (let pass = 0; pass < MAX_CLEANUP_PASSES; pass++) {
    if ((await cleanHouseholdOnce(db, bucket, householdRef)) === 0) return;
  }
  throw new HttpsError("aborted", "The household is still being written to. Try again.");
}

/**
 * Removes the household document and unlinks the caller's profile in one transaction.
 *
 * The household is read again inside the transaction and only deleted when it is the same instance
 * that was authorized and cleaned ([authorized]: its snapshot, or null when it was missing). One
 * that appeared or was replaced since is left alone, and so is the profile link that may belong to
 * it. A household that is gone is not an error. The profile is only unlinked while it still points
 * at this household.
 */
function removeHouseholdAndLink(db, householdRef, userRef, authorized) {
  return db.runTransaction(async (transaction) => {
    const household = await transaction.get(householdRef);
    const user = await transaction.get(userRef);
    if (household.exists) {
      if (!isSameInstance(household, authorized)) return;
      transaction.delete(householdRef);
    }
    if (user.exists && user.get(HOUSEHOLD_ID_FIELD) === householdRef.id) {
      transaction.set(userRef, {
        [HOUSEHOLD_ID_FIELD]: FieldValue.delete(),
        [JOIN_CODE_FIELD]: FieldValue.delete(),
      }, { merge: true });
    }
  });
}

/** True when [current] is the very document [authorized] was read from, not one created since. */
function isSameInstance(current, authorized) {
  return authorized !== null && current.createTime.isEqual(authorized.createTime);
}

/**
 * Deletes the caller's private household with everything in it: its items and their photos, its
 * receipts, its sub-collections and any incident photos. It cleans until a pass finds nothing. The
 * household document goes last, in the same transaction that unlinks the caller's profile, so a
 * failed attempt leaves the household in place and calling again only redoes what is left. A
 * household that no longer exists counts as already deleted, so a retry after a lost response
 * succeeds.
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
  const snapshot = await householdRef.get();
  let authorized = null;
  if (snapshot.exists) {
    assertDeletableBy(uid, snapshot.data());
    authorized = snapshot;
    await cleanHousehold(db, bucket, householdRef);
  }
  await removeHouseholdAndLink(db, householdRef, db.collection(USERS).doc(uid), authorized);
  return { [REQUEST_FIELD]: householdId };
}

module.exports = { deletePrivateHousehold, PAGE_SIZE, MAX_CLEANUP_PASSES };
