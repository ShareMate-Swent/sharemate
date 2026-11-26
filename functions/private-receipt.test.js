// Co-authored-by: OpenAI Codex <noreply@openai.com>
const { test, after } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const { initializeApp, deleteApp } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');
const { functions: emulator } = require('../firebase.json').emulators;
if (!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_AUTH_EMULATOR_HOST || !process.env.FIREBASE_STORAGE_EMULATOR_HOST)
  throw new Error('Run with Auth, Firestore, Storage and Functions emulators.');
const project = process.env.GCLOUD_PROJECT || 'demo-sharemate';
const app = initializeApp({ projectId: project }, 'private-receipt-tests');
const db = getFirestore(app);
after(() => deleteApp(app));

async function account() {
  const response = await fetch(`http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signUp?key=fake`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ returnSecureToken: true }),
  });
  assert.equal(response.status, 200);
  return response.json();
}
function fields(data) {
  return Object.fromEntries(Object.entries(data).map(([k, v]) => [k,
    typeof v === 'boolean' ? { booleanValue: v } : typeof v === 'number' ? { integerValue: String(v) } : { stringValue: v }]));
}
async function document(path, user, method = 'GET', data) {
  return fetch(`http://${process.env.FIRESTORE_EMULATOR_HOST}/v1/projects/${project}/databases/(default)/documents/${path}`, {
    method, headers: { authorization: `Bearer ${user.idToken}`, 'content-type': 'application/json' },
    body: data && JSON.stringify({ fields: fields(data) }),
  });
}
async function correct(data, user) {
  const host = process.env.SETTLEMENT_FUNCTIONS_HOST || `${emulator.host}:${emulator.port}`;
  const response = await fetch(`http://${host}/${project}/europe-west6/correctReceipt`, {
    method: 'POST', headers: { authorization: `Bearer ${user.idToken}`, 'content-type': 'application/json' },
    body: JSON.stringify({ data }),
  });
  return response.json();
}

test('private owner saves a photo and corrects a receipt through the callable without shared expenses', async () => {
  const [owner, stranger] = await Promise.all([account(), account()]);
  const id = randomUUID(), home = `private-receipt-${randomUUID()}`;
  const root = `users/${owner.localId}`;
  const backupPath = `${root}/receiptBackups/${id}`;
  const photoPath = `receiptPhotos/${home}/${owner.localId}/${id}.jpg`;
  const rows = [{ id: 'milk', name: 'Milk', quantity: 1, priceCents: '200' }];
  const backup = { ownerId: owner.localId, householdId: home, draft: false,
    submission: '{}', title: 'Private', scannedAtMillis: 1, photoPath, photoReady: false, revision: 0 };
  await db.doc(`households/${home}`).set({ type: 'PRIVATE', createdBy: owner.localId, memberIds: [owner.localId] });
  assert.equal((await document(backupPath, owner, 'PATCH', backup)).status, 403, 'saved backup requires confirmed inventory');
  await db.doc(`${root}/inventorySubmissions/${id}`).set({ id, ownerId: owner.localId, householdId: home,
    rows: rows.map(r => ({ ...r, priceCents: Number(r.priceCents) })) });
  await db.doc(`items/${id}_0`).set({ id: `${id}_0`, ownerId: owner.localId, householdId: home,
    name: 'Milk', quantity: 1, sharing: 'PERSONAL', status: 'ACTIVE' });
  const saved = await document(backupPath, owner, 'PATCH', backup);
  assert.equal(saved.status, 200, await saved.text());
  const storage = `http://${process.env.FIREBASE_STORAGE_EMULATOR_HOST}/v0/b/${project}.appspot.com/o`;
  const photoBytes = Buffer.from('private receipt photo');
  const uploaded = await fetch(`${storage}?uploadType=media&name=${encodeURIComponent(photoPath)}`, {
    method: 'POST', headers: { authorization: `Bearer ${owner.idToken}`, 'content-type': 'image/jpeg' }, body: photoBytes,
  });
  assert.equal(uploaded.status, 200, await uploaded.text());
  assert.equal((await document(backupPath, owner, 'PATCH', { ...backup, photoReady: true })).status, 200);
  const payload = { operationId: randomUUID(), ownerId: owner.localId, receipt: { id, householdId: home },
    originalRows: rows, originalTitle: 'Private', originalRevision: 0, originalExpense: null, expense: null,
    title: 'Private edited', rows: [...rows, { id: 'bread', name: 'Bread', quantity: 2, priceCents: '300' }] };
  assert.equal((await correct(payload, stranger)).error?.status, 'PERMISSION_DENIED');
  assert.deepEqual((await correct(payload, owner)).result, { operationId: payload.operationId });
  assert.deepEqual((await correct(payload, owner)).result, { operationId: payload.operationId });
  const receipt = (await db.doc(backupPath).get()).data();
  assert.equal(receipt.title, 'Private edited');
  assert.equal(receipt.revision, 1);
  assert.equal(receipt.draft, false);
  assert.equal(receipt.photoReady, true);
  assert.equal(JSON.parse(receipt.submission).rows.length, 2);
  assert.equal(JSON.parse(receipt.submission).sharing, 'PERSONAL');
  assert.equal((await db.doc(`${root}/expenseSubmissions/${id}`).get()).exists, false);
  assert.equal((await db.collection(`households/${home}/expenses`).get()).empty, true);
  const items = await db.collection('items').where('householdId', '==', home).get();
  assert.equal(items.size, 2);
  assert.ok(items.docs.every(d => d.data().sharing === 'PERSONAL'));
  const photo = await fetch(`${storage}/${encodeURIComponent(photoPath)}?alt=media`, { headers: { authorization: `Bearer ${owner.idToken}` } });
  assert.equal(photo.status, 200);
  assert.deepEqual(Buffer.from(await photo.arrayBuffer()), photoBytes);
  assert.equal((await document(backupPath, stranger)).status, 403);
  assert.equal((await document(backupPath, owner, 'PATCH', { ...backup, title: 'Bypass correction', revision: 1 })).status, 403);
  assert.equal((await correct({ ...payload, operationId: randomUUID() }, owner)).error?.status, 'FAILED_PRECONDITION');
});
