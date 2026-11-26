// Co-authored-by: OpenAI Codex <noreply@openai.com>
const { before, after, test } = require("node:test");
const assert = require("node:assert/strict");
const { randomUUID } = require("node:crypto");
const { initializeApp, deleteApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const { suggestRecipes } = require("./recipes");
const projectId = process.env.GCLOUD_PROJECT || "demo-sharemate";
assert.ok(projectId.startsWith("demo-") && process.env.FIRESTORE_EMULATOR_HOST &&
  process.env.FIREBASE_AUTH_EMULATOR_HOST, "Run with local Auth and Firestore emulators");
const app = initializeApp({ projectId }, `recipes-${randomUUID()}`);
const db = getFirestore(app);
const home = db.collection("households").doc(`recipes-${randomUUID()}`);
let member, outsider;
async function account() {
  const response = await fetch(`http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signUp?key=fake-key`,
    { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ returnSecureToken: true }) });
  const user = await response.json();
  return (await getAuth(app).verifyIdToken(user.idToken)).uid;
}
before(async () => {
  [member, outsider] = await Promise.all([account(), account()]);
  await home.set({ type: "SHARED", memberIds: [member] });
});
after(async () => {
  await home.delete();
  await Promise.all([member, outsider].filter(Boolean).map(uid => getAuth(app).deleteUser(uid)));
  await deleteApp(app);
});
test("real authenticated membership authorizes mapping; revocation and outsiders block HTTP", async () => {
  const data = { householdId: home.id, ingredients: ["milk"] };
  let calls = 0;
  const transport = async () => { calls++; return { ok: true, status: 200, text: async () => JSON.stringify([
    { id: 12, title: "Soup", usedIngredientCount: 1, missedIngredientCount: 0 }]) }; };
  await assert.rejects(suggestRecipes(db, null, data, () => "test-key", transport), { code: "unauthenticated" });
  await assert.rejects(suggestRecipes(db, outsider, data, () => "test-key", transport), { code: "permission-denied" });
  const result = await suggestRecipes(db, member, data, () => "test-key", transport);
  assert.equal(result.recipes[0].title, "Soup");
  await home.update({ memberIds: [] });
  await assert.rejects(suggestRecipes(db, member, data, () => "test-key", transport), { code: "permission-denied" });
  assert.equal(calls, 1);
});
