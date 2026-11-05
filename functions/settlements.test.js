const { after, before, test } = require("node:test");
const assert = require("node:assert/strict");
const { initializeApp, deleteApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");

const projectId = "demo-sharemate";
const functionsHost = process.env.SETTLEMENT_FUNCTIONS_HOST || "127.0.0.1:5001";
const householdId = "settlement-tests";
const emulatorReady = process.env.FIRESTORE_EMULATOR_HOST && process.env.FIREBASE_AUTH_EMULATOR_HOST;
if (!emulatorReady) throw new Error("Run with the Auth, Firestore and Functions emulators.");

const app = initializeApp({ projectId });
const db = getFirestore(app);
const household = db.collection("households").doc(householdId);
const settlements = household.collection("settlements");
const expense = household.collection("expenses").doc("expense");
let payer, recipient, outsider, otherMember;

async function account() {
  const host = process.env.FIREBASE_AUTH_EMULATOR_HOST;
  const response = await fetch(
    `http://${host}/identitytoolkit.googleapis.com/v1/accounts:signUp?key=fake-key`,
    { method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ returnSecureToken: true }) },
  );
  assert.ok(response.ok, await response.clone().text());
  return response.json();
}

async function call(payment, user = payer) {
  const response = await fetch(
    `http://${functionsHost}/${projectId}/europe-west6/recordSettlement`,
    { method: "POST",
      headers: { "content-type": "application/json",
        ...(user && { authorization: `Bearer ${user.idToken}` }) },
      body: JSON.stringify({ data: payment }) },
  );
  return response.json();
}

function payment(id, amountCents, overrides = {}) {
  return { id, householdId, payerId: payer.localId, recipientId: recipient.localId,
    amountCents: String(amountCents), ...overrides };
}

function settlementDocument(id, method, user, fields) {
  const host = process.env.FIRESTORE_EMULATOR_HOST;
  const path = `households/${householdId}/settlements/${id}`;
  return fetch(`http://${host}/v1/projects/${projectId}/databases/(default)/documents/${path}`,
    { method, headers: { authorization: `Bearer ${user.idToken}`,
      "content-type": "application/json" }, body: fields && JSON.stringify({ fields }) });
}

before(async () => {
  [payer, recipient, outsider, otherMember] = await Promise.all(
    Array.from({ length: 4 }, account),
  );
  await household.set({
    type: "SHARED", memberIds: [payer.localId, recipient.localId, otherMember.localId],
  });
  await expense.set({
    id: expense.id, householdId, paidBy: recipient.localId,
    totalAmountCents: 1500, shares: { [payer.localId]: 1500 },
  });
  await db.collection("households").doc("other").set({
    type: "SHARED", memberIds: [payer.localId, outsider.localId],
  });
});

after(() => deleteApp(app));

test("rejects unauthenticated, forged, outsider, cross-household and invalid payments", async () => {
  const attempt = payment("denied", 600);
  const cases = [
    [attempt, null, "UNAUTHENTICATED"],
    [attempt, otherMember, "PERMISSION_DENIED"],
    [payment("denied", 600, { payerId: outsider.localId }), outsider, "PERMISSION_DENIED"],
    [payment("denied", 600, { householdId: "other" }), payer, "FAILED_PRECONDITION"],
    [payment("denied", 600, { recipientId: outsider.localId }), payer, "FAILED_PRECONDITION"],
    [payment("denied", 600, { recipientId: otherMember.localId }), payer, "FAILED_PRECONDITION"],
    [payment("denied", 1501), payer, "FAILED_PRECONDITION"],
    [payment("denied", 0), payer, "INVALID_ARGUMENT"],
    [payment("../denied", 600), payer, "INVALID_ARGUMENT"],
  ];
  for (const [payload, user, status] of cases) {
    assert.equal((await call(payload, user)).error?.status, status);
  }
  assert.equal((await settlements.get()).size, 0);
});

test("rejects a malformed expense atomically and lets the same operation retry", async () => {
  const partial = payment("partial", 600);
  await expense.update({ totalAmountCents: 1501 });
  try {
    assert.equal((await call(partial)).error?.status, "FAILED_PRECONDITION");
    assert.equal((await settlements.get()).size, 0);
  } finally {
    await expense.update({ totalAmountCents: 1500 });
  }

  const retries = await Promise.all([call(partial), call(partial)]);
  for (const response of retries) assert.deepEqual(response.result, partial);
  assert.equal((await settlements.get()).size, 1);
  assert.equal((await call(payment("partial", 601))).error?.status, "ALREADY_EXISTS");
  assert.deepEqual((await call(partial)).result, partial);
});

test("rules allow member reads and deny direct creation, updates and deletion", async () => {
  assert.equal((await settlementDocument("partial", "GET", recipient)).status, 200);
  assert.equal((await settlementDocument("partial", "GET", outsider)).status, 403);
  const fields = { amountCents: { integerValue: "1" } };
  assert.equal((await settlementDocument("forged", "PATCH", payer, fields)).status, 403);
  assert.equal((await settlementDocument("partial", "PATCH", payer, fields)).status, 403);
  assert.equal((await settlementDocument("partial", "DELETE", payer)).status, 403);
  assert.equal((await settlements.doc("partial").get()).get("amountCents"), 600);
});

test("revoked members cannot read or retry previously committed operations", async () => {
  await household.update({ memberIds: [recipient.localId, otherMember.localId] });
  assert.equal((await call(payment("partial", 600))).error?.status, "PERMISSION_DENIED");
  assert.equal((await settlementDocument("partial", "GET", payer)).status, 403);
  assert.equal((await settlements.get()).size, 1);
});

test("knowing the household ID cannot forge membership and gain settlement access", async () => {
  const id = "protected-household";
  await db.collection("households").doc(id).set({
    type: "SHARED", memberIds: [recipient.localId], inviteCode: "ACTUAL",
  });
  const database = `projects/${projectId}/databases/(default)`;
  const endpoint = `http://${process.env.FIRESTORE_EMULATOR_HOST}/v1/${database}/documents:commit`;
  const join = (code) => fetch(endpoint, {
    method: "POST",
    headers: { authorization: `Bearer ${outsider.idToken}`, "content-type": "application/json" },
    body: JSON.stringify({ writes: [
      { transform: {
        document: `${database}/documents/households/${id}`,
        fieldTransforms: [{ fieldPath: "memberIds",
          appendMissingElements: { values: [{ stringValue: outsider.localId }] } }],
      } },
      { update: { name: `${database}/documents/users/${outsider.localId}`, fields: {
        householdId: { stringValue: id }, householdJoinCode: { stringValue: code },
      } } },
    ] }),
  });

  assert.equal((await join("GUESSED")).status, 403);
  const attempted = payment("forged-join", 1, { householdId: id, payerId: outsider.localId });
  assert.equal((await call(attempted, outsider)).error?.status, "PERMISSION_DENIED");
  assert.equal((await join("ACTUAL")).status, 200);
  assert.equal((await call(attempted, outsider)).error?.status, "FAILED_PRECONDITION");
});

test("serializes competing payments against the remaining 900 cents", async () => {
  const id = "concurrent-payments";
  const parent = db.collection("households").doc(id);
  await parent.set({ type: "SHARED", memberIds: [payer.localId, recipient.localId] });
  await parent.collection("expenses").doc("expense").set({
    id: "expense", householdId: id, paidBy: recipient.localId,
    totalAmountCents: 1500, shares: { [payer.localId]: 1500 },
  });

  const partial = payment("partial", 600, { householdId: id });
  assert.deepEqual((await call(partial)).result, partial);
  const attempts = ["finish-one", "finish-two"].map((key) =>
    payment(key, 900, { householdId: id }),
  );
  const results = await Promise.all(attempts.map((attempt) => call(attempt)));
  assert.equal(results.filter(({ result }) => result).length, 1);
  assert.equal(results.find(({ error }) => error)?.error.status, "FAILED_PRECONDITION");
  const history = await parent.collection("settlements").get();
  assert.equal(history.size, 2);
  assert.equal(history.docs.reduce((sum, doc) => sum + doc.get("amountCents"), 0), 1500);
});

test("a current payer can settle a debt to a former member", async () => {
  const id = "former-recipient";
  const parent = db.collection("households").doc(id);
  await parent.set({ type: "SHARED", memberIds: [payer.localId, recipient.localId] });
  await parent.collection("expenses").doc("expense").set({
    id: "expense", householdId: id, paidBy: recipient.localId,
    totalAmountCents: 1500, shares: { [payer.localId]: 1500 },
  });
  await parent.update({ memberIds: [payer.localId] });

  const reimbursement = payment("reimburse", 1500, { householdId: id });
  assert.deepEqual((await call(reimbursement)).result, reimbursement);
  assert.deepEqual((await call(reimbursement)).result, reimbursement);
  const history = await parent.collection("settlements").get();
  assert.equal(history.size, 1);
  assert.equal(history.docs[0].get("amountCents"), 1500);
  const extra = payment("extra", 1, { householdId: id });
  assert.equal((await call(extra)).error?.status, "FAILED_PRECONDITION");
});
