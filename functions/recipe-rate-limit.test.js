// Co-authored-by: OpenAI Codex <noreply@openai.com>
const { before, after, test } = require("node:test");
const assert = require("node:assert/strict");
const { randomUUID } = require("node:crypto");
const { initializeApp, deleteApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const { reserveRecipeRequest, recipeBudgetDocument } = require("./recipe-rate-limit");
const { suggestRecipes } = require("./recipes");
const projectId = process.env.GCLOUD_PROJECT || "demo-sharemate";
assert.ok(projectId.startsWith("demo-") && process.env.FIRESTORE_EMULATOR_HOST &&
  process.env.FIREBASE_AUTH_EMULATOR_HOST, "Run with local Auth and Firestore emulators");
const app = initializeApp({ projectId }, `recipe-limits-${randomUUID()}`);
const db = getFirestore(app);
const refs = new Map();
const home = db.collection("households").doc(`limit-home-${randomUUID()}`);
const otherHome = db.collection("households").doc(`limit-other-${randomUUID()}`);
const start = Date.parse("2026-11-26T12:00:00Z");
let member, other;
function budget(uid) {
  const ref = recipeBudgetDocument(db, uid);
  refs.set(ref.path, ref);
  return ref;
}
function uid() {
  const id = `limit-${randomUUID()}`;
  budget(id);
  return id;
}
async function account() {
  const response = await fetch(`http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signUp?key=fake-key`,
    { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ returnSecureToken: true }) });
  assert.ok(response.ok);
  return response.json();
}
before(async () => {
  [member, other] = await Promise.all([account(), account()]);
  budget(member.localId); budget(other.localId);
  await Promise.all([home, otherHome].map(ref => ref.set({ type: "SHARED", memberIds: [member.localId, other.localId] })));
});
after(async () => {
  await Promise.all([...refs.values(), home, otherHome].map(ref => ref.delete()));
  await Promise.all([member, other].filter(Boolean).map(user => getAuth(app).deleteUser(user.localId)));
  await deleteApp(app);
});

test("simultaneous reservations for one UID accept only one request", async () => {
  const id = uid();
  const results = await Promise.allSettled(Array.from({ length: 6 }, () => reserveRecipeRequest(db, id, () => start)));
  assert.equal(results.filter(r => r.status === "fulfilled").length, 1);
  assert.ok(results.filter(r => r.status === "rejected").every(r => r.reason.code === "resource-exhausted"));
  assert.equal((await budget(id).get()).data().count, 1);
});

test("concurrent requests at the daily boundary never exceed ten committed reservations", async () => {
  const id = uid();
  await budget(id).set({ day: "2026-11-26", count: 9, lastAcceptedAt: start - 30000 });
  let admitted = 0;
  const results = await Promise.allSettled(Array.from({ length: 6 }, async () => {
    await reserveRecipeRequest(db, id, () => start);
    admitted++;
  }));
  assert.equal(admitted, 1);
  assert.ok(results.filter(r => r.status === "rejected").every(r => r.reason.code === "resource-exhausted"));
  assert.equal((await budget(id).get()).data().count, 10);
  await assert.rejects(reserveRecipeRequest(db, id, () => start + 30000), { code: "resource-exhausted" });
});

test("UTC rollover keeps the cooldown and starts a new daily allowance", async () => {
  const id = uid();
  const last = Date.parse("2026-11-26T23:59:50Z");
  await budget(id).set({ day: "2026-11-26", count: 10, lastAcceptedAt: last });
  await assert.rejects(reserveRecipeRequest(db, id, () => last + 10000), { code: "resource-exhausted" });
  await reserveRecipeRequest(db, id, () => last + 30000);
  assert.deepEqual((await budget(id).get()).data(), { day: "2026-11-27", count: 1, lastAcceptedAt: last + 30000 });
});

test("service enforces the same UID budget across homes and ingredients while other users remain independent", async () => {
  let calls = 0;
  const http = async () => { calls++; return { ok: true, status: 200, text: async () => "[]" }; };
  const data = { householdId: home.id, ingredients: ["milk"] };
  const outcomes = await Promise.allSettled(Array.from({ length: 5 }, () =>
    suggestRecipes(db, member.localId, data, () => "test-key", http)));
  assert.equal(outcomes.filter(r => r.status === "fulfilled").length, 1);
  assert.ok(outcomes.filter(r => r.status === "rejected").every(r => r.reason.code === "resource-exhausted"));
  await assert.rejects(suggestRecipes(db, member.localId, { householdId: otherHome.id, ingredients: ["rice"] },
    () => "test-key", http), { code: "resource-exhausted" });
  await suggestRecipes(db, other.localId, data, () => "test-key", http);
  assert.equal(calls, 2);
  assert.equal((await budget(member.localId).get()).data().count, 1);
  assert.equal((await budget(other.localId).get()).data().count, 1);
});

test("Firestore rules deny client reads, listing, creation, update and deletion of any budget", async () => {
  const ref = budget(member.localId);
  // Independent of test ordering: only Admin SDK setup may create/reset a reservation.
  await ref.set({ day: "2026-11-26", count: 10, lastAcceptedAt: start });
  const root = `http://${process.env.FIRESTORE_EMULATOR_HOST}/v1/projects/${projectId}/databases/(default)/documents/recipeRequestBudgets`;
  for (const user of [member, other, null]) {
    const newRef = budget(`client-${randomUUID()}`);
    for (const [method, url] of [["GET", `${root}/${ref.id}`], ["GET", root],
      ["POST", `${root}?documentId=${newRef.id}`], ["PATCH", `${root}/${ref.id}`], ["DELETE", `${root}/${ref.id}`]]) {
      const response = await fetch(url, { method, headers: { "content-type": "application/json",
        ...(user && { authorization: `Bearer ${user.idToken}` }) },
        ...(["POST", "PATCH"].includes(method) && { body: JSON.stringify({ fields: {
          count: { integerValue: "1" }, day: { stringValue: "2026-11-26" }, lastAcceptedAt: { integerValue: String(start - 30000) },
        } }) }) });
      assert.equal(response.status, 403, `${method}: ${await response.text()}`);
    }
  }
  assert.equal((await ref.get()).data().count, 10);
});
