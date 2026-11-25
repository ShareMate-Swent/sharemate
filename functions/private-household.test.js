// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
const { before, after, test } = require("node:test");
const assert = require("node:assert/strict");
const { initializeApp, deleteApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getStorage } = require("firebase-admin/storage");
const { functions: emulator } = require("../firebase.json").emulators;

const projectId = process.env.GCLOUD_PROJECT || "demo-sharemate";
// The bucket the callable uses: the project's default one.
const bucketName = process.env.STORAGE_BUCKET || `${projectId}.appspot.com`;
const REGION = "europe-west6";
const NEEDED = ["FIRESTORE_EMULATOR_HOST", "FIREBASE_AUTH_EMULATOR_HOST"];
const STORAGE_HOSTS = ["FIREBASE_STORAGE_EMULATOR_HOST", "STORAGE_EMULATOR_HOST"];
if (NEEDED.some((name) => !process.env[name]) || !STORAGE_HOSTS.some((name) => process.env[name])) {
  throw new Error("Run with the Auth, Firestore, Storage and Functions emulators.");
}

const app = initializeApp({ projectId, storageBucket: bucketName }, "private-household-tests");
const db = getFirestore(app);
const bucket = getStorage(app).bucket();
let owner, other;

async function account() {
  const response = await fetch(
    `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signUp?key=fake-key`,
    { method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ returnSecureToken: true }) });
  assert.ok(response.ok);
  return response.json();
}

async function call(data, user = owner) {
  const host = process.env.SETTLEMENT_FUNCTIONS_HOST || `${emulator.host}:${emulator.port}`;
  const response = await fetch(`http://${host}/${projectId}/${REGION}/deletePrivateHousehold`, {
    method: "POST", headers: { "content-type": "application/json",
      ...(user && { authorization: `Bearer ${user.idToken}` }) },
    body: JSON.stringify({ data }),
  });
  return response.json();
}

async function exists(path) {
  return (await db.doc(path).get()).exists;
}

async function files(prefix) {
  const [found] = await bucket.getFiles({ prefix });
  return found.map((file) => file.name);
}

async function seedHousehold(id, user, type = "PRIVATE") {
  await db.doc(`households/${id}`).set({
    id, name: "Home", type, inviteCode: "", memberIds: [user.localId], createdBy: user.localId });
  await db.doc(`users/${user.localId}`).set({ householdId: id, joinCode: "CODE", keep: "me" });
}

before(async () => {
  [owner, other] = await Promise.all([account(), account()]);
});
after(() => deleteApp(app));

test("deletes the household with its items, photos, receipts and sub-collections, and unlinks the profile", async () => {
  const id = `${owner.localId}:full`;
  await seedHousehold(id, owner);
  await db.doc(`households/${id}/memberNames/${owner.localId}`).set({ displayName: "Owner" });
  await db.doc("items/full-1").set({ householdId: id, ownerId: owner.localId });
  await db.doc("items/full-2").set({ householdId: id, ownerId: owner.localId });
  await db.doc("receipts/full-1").set({ householdId: id, ownerId: owner.localId });
  await db.doc(`users/${owner.localId}/receiptBackups/full-1`).set({ householdId: id, ownerId: owner.localId });
  await db.doc(`users/${owner.localId}/receiptDeletions/full-2`).set({ householdId: id });
  await bucket.file("itemPhotos/full-1/photo").save("jpeg");
  await bucket.file("itemPhotos/full-2/photo").save("jpeg");
  await bucket.file(`receiptPhotos/${id}/${owner.localId}/full-1.jpg`).save("jpeg");
  await bucket.file(`incidentPhotos/${id}/${owner.localId}/photo`).save("jpeg");

  const response = await call({ householdId: id });

  assert.deepEqual(response, { result: { householdId: id } });
  for (const path of [`households/${id}`, `households/${id}/memberNames/${owner.localId}`,
    "items/full-1", "items/full-2", "receipts/full-1",
    `users/${owner.localId}/receiptBackups/full-1`, `users/${owner.localId}/receiptDeletions/full-2`]) {
    assert.equal(await exists(path), false, path);
  }
  assert.deepEqual(await files("itemPhotos/full-"), []);
  assert.deepEqual(await files(`incidentPhotos/${id}/`), []);
  assert.deepEqual(await files(`receiptPhotos/${id}/`), []);
  const profile = (await db.doc(`users/${owner.localId}`).get()).data();
  assert.deepEqual(profile, { keep: "me" });
});

test("leaves other households, other items, photos and personal receipts alone", async () => {
  const id = `${owner.localId}:scoped`;
  const sharedId = `${owner.localId}:shared-scoped`;
  await seedHousehold(id, owner);
  await db.doc(`households/${sharedId}`).set({
    type: "SHARED", memberIds: [owner.localId, other.localId], createdBy: owner.localId });
  await db.doc("items/scoped-mine").set({ householdId: id, ownerId: owner.localId });
  await db.doc("items/scoped-shared").set({ householdId: sharedId, ownerId: owner.localId });
  await db.doc("receipts/scoped-personal").set({ ownerId: owner.localId });
  await db.doc(`users/${owner.localId}/receiptBackups/scoped-shared`).set({ householdId: sharedId, ownerId: owner.localId });
  await bucket.file("itemPhotos/scoped-shared/photo").save("jpeg");
  await bucket.file(`receiptPhotos/${sharedId}/${owner.localId}/scoped.jpg`).save("jpeg");

  await call({ householdId: id });

  assert.equal(await exists("items/scoped-mine"), false);
  assert.equal(await exists("items/scoped-shared"), true);
  assert.equal(await exists(`households/${sharedId}`), true);
  assert.equal(await exists("receipts/scoped-personal"), true);
  assert.deepEqual(await files("itemPhotos/scoped-shared/"), ["itemPhotos/scoped-shared/photo"]);
  assert.equal(await exists(`users/${owner.localId}/receiptBackups/scoped-shared`), true);
  assert.deepEqual(await files(`receiptPhotos/${sharedId}/`), [`receiptPhotos/${sharedId}/${owner.localId}/scoped.jpg`]);
});

test("deleting twice succeeds, and the second call removes the caller's own leftovers only", async () => {
  const id = `${owner.localId}:twice`;
  await seedHousehold(id, owner);
  await call({ householdId: id });
  await db.doc("items/twice-mine").set({ householdId: id, ownerId: owner.localId });
  await db.doc("receipts/twice-mine").set({ householdId: id, ownerId: owner.localId });
  await db.doc(`users/${owner.localId}/receiptBackups/twice-mine`).set({ householdId: id, ownerId: owner.localId });
  await db.doc("receipts/twice-theirs").set({ householdId: id, ownerId: other.localId });
  await db.doc("items/twice-nobody").set({ householdId: id });
  await bucket.file("itemPhotos/twice-mine/photo").save("jpeg");
  await bucket.file(`receiptPhotos/${id}/${owner.localId}/mine.jpg`).save("jpeg");
  await bucket.file(`receiptPhotos/${id}/${other.localId}/theirs.jpg`).save("jpeg");

  assert.deepEqual(await call({ householdId: id }), { result: { householdId: id } });

  for (const path of ["items/twice-mine", "receipts/twice-mine", `users/${owner.localId}/receiptBackups/twice-mine`]) {
    assert.equal(await exists(path), false, path);
  }
  assert.deepEqual(await files("itemPhotos/twice-mine/"), []);
  assert.deepEqual(await files(`receiptPhotos/${id}/${owner.localId}/`), []);
  assert.equal(await exists("receipts/twice-theirs"), true);
  assert.equal(await exists("items/twice-nobody"), true);
  assert.deepEqual(await files(`receiptPhotos/${id}/${other.localId}/`), [`receiptPhotos/${id}/${other.localId}/theirs.jpg`]);
});

test("refuses another user and a shared household, and deletes nothing", async () => {
  const id = `${owner.localId}:refused`;
  const sharedId = `${owner.localId}:refused-shared`;
  await seedHousehold(id, owner);
  await db.doc(`households/${sharedId}`).set({
    type: "SHARED", memberIds: [owner.localId], createdBy: owner.localId });
  await db.doc("items/refused-1").set({ householdId: id, ownerId: owner.localId });

  const stranger = await call({ householdId: id }, other);
  const shared = await call({ householdId: sharedId });

  assert.equal(stranger.error.status, "PERMISSION_DENIED");
  assert.equal(shared.error.status, "FAILED_PRECONDITION");
  assert.equal(await exists(`households/${id}`), true);
  assert.equal(await exists("items/refused-1"), true);
  assert.equal(await exists(`households/${sharedId}`), true);
});

test("requires a signed-in caller and a well-formed request", async () => {
  assert.equal((await call({ householdId: `${owner.localId}:x` }, null)).error.status, "UNAUTHENTICATED");
  assert.equal((await call({})).error.status, "INVALID_ARGUMENT");
  assert.equal((await call({ householdId: "a/b" })).error.status, "INVALID_ARGUMENT");
});
