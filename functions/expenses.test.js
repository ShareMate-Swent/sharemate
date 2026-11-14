// Co-authored-by: OpenAI Codex <noreply@openai.com>
const { before, after, test } = require("node:test");
const assert = require("node:assert/strict");
const { initializeApp, deleteApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { functions: emulator } = require("../firebase.json").emulators;
const projectId = process.env.GCLOUD_PROJECT || "demo-sharemate";
if (!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_AUTH_EMULATOR_HOST) {
  throw new Error("Run with the Auth, Firestore and Functions emulators.");
}
const app = initializeApp({ projectId }, "expense-tests");
const db = getFirestore(app);
db.settings({ useBigInt: true });
const householdId = "expense-tests";
const household = db.collection("households").doc(householdId);
const expenses = household.collection("expenses");
let author, payer, outsider;

async function account() {
  const response = await fetch(
    `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signUp?key=fake-key`,
    { method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ returnSecureToken: true }) });
  assert.ok(response.ok);
  return response.json();
}

function purchase(id, changes = {}) {
  return { id, householdId, paidBy: payer.localId, totalAmountCents: "1000",
    shares: { [author.localId]: "500", [payer.localId]: "500" }, ...changes };
}

async function call(data, user = author) {
  const host = process.env.SETTLEMENT_FUNCTIONS_HOST || `${emulator.host}:${emulator.port}`;
  const response = await fetch(`http://${host}/${projectId}/europe-west6/recordExpense`, {
    method: "POST", headers: { "content-type": "application/json",
      ...(user && { authorization: `Bearer ${user.idToken}` }) },
    body: JSON.stringify({ data }),
  });
  return response.json();
}

async function direct(id, method, user, fields) {
  const path = `households/${householdId}/expenses/${id}`;
  return fetch(`http://${process.env.FIRESTORE_EMULATOR_HOST}/v1/projects/${projectId}/databases/(default)/documents/${path}`, {
    method, headers: { authorization: `Bearer ${user.idToken}`, "content-type": "application/json" },
    body: fields && JSON.stringify({ fields }),
  });
}

before(async () => {
  [author, payer, outsider] = await Promise.all(Array.from({ length: 3 }, account));
  await household.set({ type: "SHARED", memberIds: [author.localId, payer.localId] });
});
after(() => deleteApp(app));

test("authorizes caller, payer and every participant using the selected household", async () => {
  await db.collection("households").doc("expense-other").set({
    type: "SHARED", memberIds: [author.localId, outsider.localId],
  });
  await db.collection("households").doc("expense-private").set({
    type: "PRIVATE", memberIds: [author.localId, payer.localId],
  });
  const attempts = [
    [purchase("denied"), null, "UNAUTHENTICATED"],
    [purchase("denied"), outsider, "PERMISSION_DENIED"],
    [purchase("denied", { paidBy: outsider.localId }), author, "PERMISSION_DENIED"],
    [purchase("denied", { shares: { [outsider.localId]: "1000" } }), author, "PERMISSION_DENIED"],
    [purchase("denied", { householdId: "expense-other" }), author, "PERMISSION_DENIED"],
    [purchase("denied", { householdId: "expense-private" }), author, "PERMISSION_DENIED"],
    [purchase("denied", { householdId: "expense-missing" }), author, "PERMISSION_DENIED"],
    [purchase("denied", { ownerId: author.localId }), outsider, "INVALID_ARGUMENT"],
  ];
  for (const [payload, user, status] of attempts) {
    assert.equal((await call(payload, user)).error?.status, status);
  }
  assert.equal((await expenses.get()).size, 0);
});

test("rejects malformed money, IDs and allocations without creating any expense", async () => {
  const attempts = [null, [], {},
    ...["", " ", ".", "..", "a/b", "__reserved__", "x".repeat(1501)].map((id) => purchase(id)),
    ...[0, 1, null, "", "0", "-1", "1.5", "01", "9223372036854775808"].map((totalAmountCents) =>
      purchase("invalid", { totalAmountCents })),
    ...[null, [], {}, { [author.localId]: "999" }, { [author.localId]: "1001" },
      { [author.localId]: 1000 }, { [author.localId]: "-1" }, { "a/b": "1000" },
      { [author.localId]: "9223372036854775808" }].map((shares) => purchase("invalid", { shares })),
    purchase("invalid", { paidBy: "a/b" }), purchase("invalid", { householdId: "a/b" }),
  ];
  for (const payload of attempts) {
    assert.equal((await call(payload)).error?.status, "INVALID_ARGUMENT", JSON.stringify(payload));
  }
  assert.equal((await expenses.get()).size, 0);
});

test("concurrent identical retries commit exactly one expense and preserve the ledger schema", async () => {
  const payload = purchase("retry");
  const results = await Promise.all([call(payload), call(payload)]);
  results.forEach((response) => assert.deepEqual(response.result, payload));
  assert.deepEqual((await expenses.doc("retry").get()).data(), {
    ...payload, totalAmountCents: 1000n,
    shares: { [author.localId]: 500n, [payer.localId]: 500n },
  });
  assert.equal((await expenses.get()).size, 1);
  assert.equal((await household.collection("financeMetadata").doc("settlementRevision").get())
    .get("lastExpenseId"), "retry");
  const reordered = purchase("retry", { shares: { [payer.localId]: "500", [author.localId]: "500" } });
  assert.deepEqual((await call(reordered)).result, reordered);
  assert.equal((await call(payload, payer)).error?.status, "ALREADY_EXISTS");
  for (const changes of [
    { paidBy: author.localId },
    { totalAmountCents: "1001", shares: { [author.localId]: "501", [payer.localId]: "500" } },
    { shares: { [author.localId]: "1000" } },
  ]) assert.equal((await call(purchase("retry", changes))).error?.status, "ALREADY_EXISTS");
});

test("binds the submitter operation to one household, even with valid membership in both", async () => {
  const other = db.collection("households").doc("expense-second");
  await other.set({ type: "SHARED", memberIds: [author.localId, payer.localId] });
  assert.equal((await call(purchase("retry", { householdId: other.id }))).error?.status, "ALREADY_EXISTS");
  assert.equal((await other.collection("expenses").get()).size, 0);
  const payload = purchase("second-operation", { householdId: other.id });
  assert.deepEqual((await call(payload)).result, payload);
});

test("preserves 64-bit cents, accepts zero shares and lets payer consume no share", async () => {
  for (const amount of ["1", "9223372036854775807"]) {
    const payload = purchase(`amount-${amount}`, {
      totalAmountCents: amount, shares: { [author.localId]: amount, [payer.localId]: "0" },
    });
    assert.deepEqual((await call(payload)).result, payload);
    assert.equal((await expenses.doc(payload.id).get()).get("totalAmountCents"), BigInt(amount));
  }
});

test("rules permit member reads and deny direct expense and operation marker tampering", async () => {
  assert.equal((await direct("retry", "GET", payer)).status, 200);
  assert.equal((await direct("retry", "GET", outsider)).status, 403);
  const fields = { totalAmountCents: { integerValue: "1" } };
  for (const [id, method] of [["forged", "PATCH"], ["retry", "PATCH"], ["retry", "DELETE"]]) {
    assert.equal((await direct(id, method, author, method === "PATCH" ? fields : undefined)).status, 403);
  }
  const marker = `users/${author.localId}/expenseSubmissions/retry`;
  const response = await fetch(`http://${process.env.FIRESTORE_EMULATOR_HOST}/v1/projects/${projectId}/databases/(default)/documents/${marker}`, {
    method: "PATCH", headers: { authorization: `Bearer ${author.idToken}`, "content-type": "application/json" },
    body: JSON.stringify({ fields: { householdId: { stringValue: "forged" } } }),
  });
  assert.equal(response.status, 403);
  assert.equal((await expenses.doc("retry").get()).get("totalAmountCents"), 1000n);
});

test("retains historical shares but revokes caller access before checking replay", async () => {
  await household.update({ memberIds: [author.localId] });
  assert.deepEqual((await call(purchase("retry"))).result, purchase("retry"));
  assert.equal((await call(purchase("departed-payer"))).error?.status, "PERMISSION_DENIED");
  await household.update({ memberIds: [payer.localId] });
  assert.equal((await call(purchase("retry"))).error?.status, "PERMISSION_DENIED");
  assert.equal((await direct("retry", "GET", author)).status, 403);
  assert.equal((await expenses.doc("retry").get()).exists, true);
});
