// Co-authored-by: OpenAI Codex <noreply@openai.com>
const { before, after, test } = require("node:test");
const assert = require("node:assert/strict");
const { initializeApp, deleteApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { functions: emulator } = require("../firebase.json").emulators;
if (!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_AUTH_EMULATOR_HOST)
  throw new Error("Run with Auth, Firestore and Functions emulators.");
const projectId = process.env.GCLOUD_PROJECT || "demo-sharemate";
const app = initializeApp({ projectId }, "receipt-corrections-tests");
const db = getFirestore(app);
db.settings({ useBigInt: true });
const homeId = "receipt-corrections-tests";
const home = db.collection("households").doc(homeId);
let author, other;
async function account() {
  const response = await fetch(`http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signUp?key=fake-key`, {
    method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ returnSecureToken: true }),
  });
  assert.ok(response.ok);
  return response.json();
}
async function call(data, user = author) {
  const host = process.env.SETTLEMENT_FUNCTIONS_HOST || `${emulator.host}:${emulator.port}`;
  return (await fetch(`http://${host}/${projectId}/europe-west6/correctReceipt`, {
    method: "POST", headers: { "content-type": "application/json", ...(user && { authorization: `Bearer ${user.idToken}` }) },
    body: JSON.stringify({ data }),
  })).json();
}
const decodeExpense = value => ({ ...value, totalAmountCents: BigInt(value.totalAmountCents),
  shares: Object.fromEntries(Object.entries(value.shares).map(([id, amount]) => [id, BigInt(amount)])) });
const decodeRows = rows => rows.map(row => ({ ...row, quantity: BigInt(row.quantity), priceCents: BigInt(row.priceCents) }));
async function seed(id) {
  const rows = [{ id: "milk", name: "Milk", quantity: 2, priceCents: "400" }, { id: "bread", name: "Bread", quantity: 1, priceCents: "200" }];
  const expense = { id, householdId: homeId, paidBy: author.localId, totalAmountCents: "600",
    shares: { [author.localId]: "300", [other.localId]: "300" } };
  const user = db.collection("users").doc(author.localId);
  await user.collection("inventorySubmissions").doc(id).set({ id, ownerId: author.localId, householdId: homeId, rows: decodeRows(rows) });
  await user.collection("expenseSubmissions").doc(id).set({ householdId: homeId });
  await home.collection("expenses").doc(id).set(decodeExpense(expense));
  for (const [i, row] of rows.entries()) await db.collection("items").doc(`${id}_${i}`).set({
    id: `${id}_${i}`, name: row.name, quantity: BigInt(row.quantity), ownerId: author.localId,
    householdId: homeId, status: "ACTIVE", sharing: "SHARED", category: "groceries",
  });
  return { operationId: `${id}-edit`, title: "Courses Migros", originalTitle: "", originalRows: rows, originalExpense: expense,
    rows: [{ ...rows[0], name: "Oat milk", quantity: 3, priceCents: "500" }, { id: "apples", name: "Apples", quantity: 1, priceCents: "300" }],
    expense: { ...expense, paidBy: other.localId, totalAmountCents: "800", shares: { [author.localId]: "400", [other.localId]: "400" } } };
}
before(async () => {
  [author, other] = await Promise.all([account(), account()]);
  await db.recursiveDelete(home);
  const oldItems = await db.collection('items').where('householdId', '==', homeId).get();
  await Promise.all(oldItems.docs.map(doc => doc.ref.delete()));
  await home.set({ type: "SHARED", memberIds: [author.localId, other.localId] });
});

test('current member corrects another creator receipt through callable and revoked member cannot replay', async () => {
  const payload = { ...await seed('member-correction'), ownerId: author.localId };
  const backup = db.doc(`users/${author.localId}/receiptBackups/member-correction`);
  await backup.set({ ownerId: author.localId, householdId: homeId, draft: false, photoReady: true });
  try {
    const result = await call(payload, other);
    assert.deepEqual(result.result, { operationId: payload.operationId });
    const updated = (await backup.get()).data();
    assert.equal(updated.title, payload.title);
    const content = JSON.parse(updated.submission);
    assert.equal(content.ownerId, author.localId);
    assert.equal(content.rows[0].name, 'Oat milk');
    assert.equal(content.expense.totalAmountCents, 800);
    await home.update({ memberIds: [author.localId] });
    assert.equal((await call(payload, other)).error?.status, 'PERMISSION_DENIED');
  } finally {
    await backup.delete();
    await home.update({ memberIds: [author.localId, other.localId] });
    // The other test's exact item count is scoped to its own receipt fixture.
    const links = (await db.doc(`users/${author.localId}/receiptItemLinks/member-correction`).get()).data()?.links || {};
    await Promise.all(Object.values(links).map(id => db.doc(`items/${id}`).delete()));
  }
});
after(() => deleteApp(app));

test("callable corrects the same expense and fridge atomically; concurrent retries do not duplicate", async () => {
  const payload = await seed("correction-roundtrip");
  const results = await Promise.all([call(payload), call(payload)]);
  results.forEach(result => assert.deepEqual(result.result, { operationId: payload.operationId }));
  assert.deepEqual((await home.collection("expenses").doc(payload.expense.id).get()).data(), decodeExpense(payload.expense));
  const milk = (await db.collection("items").doc("correction-roundtrip_0").get()).data();
  assert.equal(milk.name, "Oat milk");
  assert.equal(milk.quantity, 3n);
  assert.equal(milk.category, "groceries");
  assert.equal((await db.collection("items").doc("correction-roundtrip_1").get()).exists, false);
  const linksRef = db.collection("users").doc(author.localId).collection("receiptItemLinks").doc(payload.expense.id);
  const links = (await linksRef.get()).data();
  assert.equal(links.title, "Courses Migros");
  assert.equal((await db.collection("items").doc(links.links.apples).get()).data().name, "Apples");
  // A second edit uses retained links, even though row order changed and a row was added.
  const second = { ...payload, operationId: "correction-second", originalRevision: 1, originalTitle: payload.title, originalRows: payload.rows, originalExpense: payload.expense,
    rows: [payload.rows[1], { ...payload.rows[0], quantity: 4 }] };
  const secondResult = await call(second);
  assert.ok(secondResult.result, JSON.stringify(secondResult));
  assert.equal((await db.collection("items").doc("correction-roundtrip_0").get()).data().quantity, 4n);
  const count = (await db.collection("items").where("householdId", "==", homeId).get()).size;
  assert.equal(count, 2);
  // Replaying an earlier successful operation after another edit must not undo the later edit.
  assert.ok((await call(payload)).result);
  assert.equal((await db.collection("items").doc("correction-roundtrip_0").get()).data().quantity, 4n);
});

test("rejects stale data and unauthorized changes without partially updating the receipt", async () => {
  const payload = await seed("correction-rejected");
  assert.equal((await call(payload, null)).error.status, "UNAUTHENTICATED");
  assert.equal((await call(payload, other)).error.status, "FAILED_PRECONDITION");
  await db.collection("items").doc("correction-rejected_0").delete();
  assert.equal((await call(payload)).error.status, "FAILED_PRECONDITION");
  assert.deepEqual((await home.collection("expenses").doc(payload.expense.id).get()).data(), decodeExpense(payload.originalExpense));
  assert.equal((await db.collection("items").doc("correction-rejected_1").get()).exists, true);
  const operation = db.collection("users").doc(author.localId).collection("receiptCorrections").doc(payload.operationId);
  assert.equal((await operation.get()).exists, false);
});


test("namespaced backups prevent a peer draft from blocking correction or deletion", async () => {
  const payload = await seed("namespace-collision");
  const id = payload.expense.id;
  const backupPath = uid => `users/${uid}/receiptBackups/${id}`;
  async function putBackup(path, account, owner) {
    const data = { ownerId: owner, householdId: homeId, draft: true, submission: "{}", title: "",
      scannedAtMillis: 1, draftPaidBy: null, photoPath: `receiptPhotos/${homeId}/${owner}/${id}.jpg`, photoReady: false };
    const fields = Object.fromEntries(Object.entries(data).map(([key, value]) => [key,
      value === null ? { nullValue: null } : typeof value === "boolean" ? { booleanValue: value }
        : typeof value === "number" ? { integerValue: String(value) } : { stringValue: value }]));
    return fetch(`http://${process.env.FIRESTORE_EMULATOR_HOST}/v1/projects/${projectId}/databases/(default)/documents/${path}`, {
      method: "PATCH", headers: { authorization: `Bearer ${account.idToken}`, "content-type": "application/json" },
      body: JSON.stringify({ fields }),
    });
  }
  assert.equal((await putBackup(backupPath(other.localId), other, other.localId)).status, 200);
  assert.equal((await putBackup(backupPath(author.localId), other, other.localId)).status, 403);
  assert.equal((await putBackup(`receiptBackups/${id}`, other, other.localId)).status, 403);
  assert.equal((await putBackup(backupPath(author.localId), author, author.localId)).status, 200);
  assert.deepEqual((await call(payload)).result, { operationId: payload.operationId });
  const peerDraft = (await db.doc(backupPath(other.localId)).get()).data();
  await require("./receipt-deletions").deleteReceipt(db, author.localId, { id, householdId: homeId });
  assert.equal((await db.doc(backupPath(author.localId)).get()).exists, false);
  assert.deepEqual((await db.doc(backupPath(other.localId)).get()).data(), peerDraft);
  await db.doc(backupPath(other.localId)).delete();
});

test("only the receipt owner can turn a saved purchase into inventory only", async () => {
  const payload = await seed("remove-charge");
  const { id, householdId } = payload.originalExpense;
  const ledger = home.collection("expenses").doc(id);
  const correction = {
    ...payload,
    ownerId: author.localId,
    receipt: { id, householdId },
    expense: null,
    rows: payload.originalRows.map(row => ({ ...row, includedInExpense: false })),
  };

  assert.equal((await call(correction, other)).error?.status, "PERMISSION_DENIED");
  assert.deepEqual((await ledger.get()).data(), decodeExpense(payload.originalExpense));
  assert.deepEqual((await call(correction)).result, { operationId: payload.operationId });
  assert.equal((await ledger.get()).exists, false);
});
