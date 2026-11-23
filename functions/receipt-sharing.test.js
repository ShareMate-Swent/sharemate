// Co-authored-by: OpenAI Codex <noreply@openai.com>
const { before, after, test } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const { initializeApp, deleteApp } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');
const { getStorage } = require('firebase-admin/storage');
if (!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_AUTH_EMULATOR_HOST || !process.env.FIREBASE_STORAGE_EMULATOR_HOST)
  throw new Error('Run against the Auth, Firestore and Storage emulators.');
const project = process.env.GCLOUD_PROJECT || 'demo-sharemate';
const app = initializeApp({ projectId: project }, 'receipt-sharing-tests');
const db = getFirestore(app);
const home = `receipt-sharing-${randomUUID()}`;
let alice, bob, outsider;
const savedId = randomUUID(), draftId = randomUUID();
async function account() {
  const response = await fetch(`http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signUp?key=fake`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ returnSecureToken: true }),
  });
  assert.equal(response.status, 200);
  return response.json();
}
function fields(value) {
  return Object.fromEntries(Object.entries(value).map(([key, v]) => [key,
    typeof v === 'boolean' ? { booleanValue: v } : typeof v === 'number' ? { integerValue: String(v) }
      : v === null ? { nullValue: null } : Array.isArray(v) ? { arrayValue: { values: v.map(stringValue => ({ stringValue })) } }
        : { stringValue: v }]));
}
async function request(path, user, method = 'GET', data) {
  return fetch(`http://${process.env.FIRESTORE_EMULATOR_HOST}/v1/projects/${project}/databases/(default)/documents/${path}`, {
    method, headers: { authorization: `Bearer ${user.idToken}`, 'content-type': 'application/json' },
    body: data && JSON.stringify({ fields: fields(data) }),
  });
}
const backup = (id, draft) => ({ ownerId: alice.localId, householdId: home, draft,
  submission: '{}', title: 'Courses', scannedAtMillis: 1, draftPaidBy: null,
  draftParticipants: [], photoPath: `receiptPhotos/${home}/${alice.localId}/${id}.jpg`, photoReady: false });
async function photo(id, user) {
  const path = encodeURIComponent(backup(id, false).photoPath);
  return fetch(`http://${process.env.FIREBASE_STORAGE_EMULATOR_HOST}/v0/b/${project}.appspot.com/o/${path}?alt=media`,
    { headers: { authorization: `Bearer ${user.idToken}` } });
}
before(async () => {
  [alice, bob, outsider] = await Promise.all([account(), account(), account()]);
  await db.collection('households').doc(home).set({ type: 'SHARED', memberIds: [alice.localId, bob.localId] });
  await db.doc(`users/${alice.localId}/expenseSubmissions/${savedId}`).set({ householdId: home });
});
after(async () => {
  await Promise.all([db.doc(`receiptBackups/${savedId}`).delete(), db.doc(`receiptBackups/${draftId}`).delete()]);
  await db.recursiveDelete(db.doc(`households/${home}`));
  await deleteApp(app);
});
test('members read Saved, only the creator reads Draft; all direct Saved edits are denied', async () => {
  assert.equal((await request(`receiptBackups/${randomUUID()}`, alice)).status, 404);
  for (const [id, draft] of [[savedId, false], [draftId, true]]) {
    const response = await request(`receiptBackups/${id}`, alice, 'PATCH', backup(id, draft));
    assert.equal(response.status, 200, await response.text());
  }
  assert.equal((await request(`receiptBackups/${savedId}`, bob)).status, 200);
  assert.equal((await request(`receiptBackups/${draftId}`, alice)).status, 200);
  assert.equal((await request(`receiptBackups/${draftId}`, bob)).status, 403);
  assert.equal((await request(`receiptBackups/${savedId}`, outsider)).status, 403);
  assert.equal((await request(`receiptBackups/${savedId}`, bob, 'PATCH', { ...backup(savedId, false), title: 'Forged' })).status, 403);
  assert.equal((await request(`receiptBackups/${savedId}`, alice, 'PATCH', { ...backup(savedId, false), title: 'Bypass correction' })).status, 403);
  assert.equal((await request(`receiptBackups/${draftId}`, alice, 'PATCH', { ...backup(draftId, true), title: 'Private edit' })).status, 200);
  assert.equal((await request(`receiptBackups/${savedId}`, alice, 'PATCH', { ...backup(savedId, false), photoReady: true })).status, 200);
});
test('leaving the household immediately revokes Saved access and stale uploads cannot resurrect deletions', async () => {
  {
    const bucket = getStorage(app).bucket(`${project}.appspot.com`);
    try {
      for (const id of [savedId, draftId]) await bucket.file(backup(id, false).photoPath).save(Buffer.from('receipt-photo'), { contentType: 'image/jpeg' });
      const read = await photo(savedId, bob);
      assert.equal(read.status, 200, await read.text());
      assert.equal((await photo(draftId, alice)).status, 200);
      assert.equal((await photo(draftId, bob)).status, 403);
      assert.equal((await photo(savedId, outsider)).status, 403);
      await db.doc(`households/${home}`).update({ memberIds: [alice.localId] });
      assert.equal((await photo(savedId, bob)).status, 403);
    } finally {
      await Promise.all([savedId, draftId].map(id => bucket.file(backup(id, false).photoPath).delete({ ignoreNotFound: true })));
    }
  }
  await db.doc(`households/${home}`).update({ memberIds: [alice.localId] });
  assert.equal((await request(`receiptBackups/${savedId}`, bob)).status, 403);
  await db.doc(`users/${alice.localId}/receiptDeletions/${draftId}`).set({ householdId: home });
  await db.doc(`receiptBackups/${draftId}`).delete();
  assert.equal((await request(`receiptBackups/${draftId}`, alice, 'PATCH', backup(draftId, true))).status, 403);
});
