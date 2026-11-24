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
 * Deletes every document of [collection] that belongs to the household, page by page. [beforeDelete]
 * runs on a page first, so a failure there leaves the documents in place for the next attempt.
 */
async function deleteHouseholdDocuments(db, collection, householdId, beforeDelete) {
  const query = db.collection(collection).where(HOUSEHOLD_ID_FIELD, "==", householdId).limit(PAGE_SIZE);
  for (;;) {
    const page = await query.get();
    if (page.empty) return;
    await beforeDelete(page.docs);
    const batch = db.batch();
    page.docs.forEach((doc) => batch.delete(doc.ref));
    await batch.commit();
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
 * Removes the household document and unlinks the caller's profile in one transaction. The profile
 * is only touched when it still points at this household. Deleting a missing household is a no-op.
 */
function removeHouseholdAndLink(db, householdRef, userRef, householdId) {
  return db.runTransaction(async (transaction) => {
    const user = await transaction.get(userRef);
    transaction.delete(householdRef);
    if (user.exists && user.get(HOUSEHOLD_ID_FIELD) === householdId) {
      transaction.set(userRef, {
        [HOUSEHOLD_ID_FIELD]: FieldValue.delete(),
        [JOIN_CODE_FIELD]: FieldValue.delete(),
      }, { merge: true });
    }
  });
}

/**
 * Deletes the caller's private household with everything in it: its items and their photos, its
 * receipts, its sub-collections and any incident photos. The household document goes last, in the
 * same transaction that unlinks the caller's profile, so a failed attempt leaves the household in
 * place and calling again only redoes what is left. A household that no longer exists counts as
 * already deleted, so a retry after a lost response succeeds.
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
  if (snapshot.exists) {
    assertDeletableBy(uid, snapshot.data());
    await deleteHouseholdDocuments(db, ITEMS, householdId, (items) => deleteItemPhotos(bucket, items));
    await deleteHouseholdDocuments(db, RECEIPTS, householdId, async () => {});
    await deleteSubcollections(db, householdRef);
    await bucket.deleteFiles({ prefix: `${INCIDENT_PHOTOS_PREFIX}${householdId}/` });
  }
  await removeHouseholdAndLink(db, householdRef, db.collection(USERS).doc(uid), householdId);
  return { [REQUEST_FIELD]: householdId };
}

module.exports = { deletePrivateHousehold, PAGE_SIZE };
