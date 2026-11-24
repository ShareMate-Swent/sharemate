// Co-authored-by: OpenAI Codex <noreply@openai.com>
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { randomUUID } = require("node:crypto");
const { initializeApp, deleteApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getStorage } = require("firebase-admin/storage");
const { cleanupHouseholdReceipts } = require("./household-receipt-cleanup");

test("deleted private household loses backups and photos; shared departure keeps both", async () => {
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST && process.env.FIREBASE_STORAGE_EMULATOR_HOST,
    "Run with Firestore and Storage emulators");
  const projectId = process.env.GCLOUD_PROJECT || "demo-sharemate-ci";
  assert.ok(projectId.startsWith("demo-"));
  const app = initializeApp({ projectId }, `cleanup-${randomUUID()}`);
  const db = getFirestore(app);
  const bucket = getStorage(app).bucket(`${projectId}.appspot.com`);
  const privateHome = `private-${randomUUID()}`, sharedHome = `shared-${randomUUID()}`;
  const rows = [privateHome, sharedHome].map(householdId => ({ householdId, id: randomUUID() }));
  try {
    for (const row of rows) {
      await db.doc(`households/${row.householdId}`).set({ type: row.householdId === privateHome ? "PRIVATE" : "SHARED", memberIds: [] });
      await db.doc(`users/alice/receiptBackups/${row.id}`).set({ householdId: row.householdId });
      await bucket.file(`receiptPhotos/${row.householdId}/alice/${row.id}.jpg`).save("photo");
    }
    await cleanupHouseholdReceipts(db, bucket, sharedHome);
    await db.doc(`households/${privateHome}`).delete();
    await cleanupHouseholdReceipts(db, bucket, privateHome);
    await cleanupHouseholdReceipts(db, bucket, privateHome);
    for (const row of rows) {
      const retained = row.householdId === sharedHome;
      assert.equal((await db.doc(`users/alice/receiptBackups/${row.id}`).get()).exists, retained);
      assert.equal((await bucket.file(`receiptPhotos/${row.householdId}/alice/${row.id}.jpg`).exists())[0], retained);
    }
  } finally {
    for (const row of rows) {
      await db.doc(`users/alice/receiptBackups/${row.id}`).delete();
      await db.doc(`households/${row.householdId}`).delete();
      await bucket.deleteFiles({ prefix: `receiptPhotos/${row.householdId}/` });
    }
    await deleteApp(app);
  }
});
