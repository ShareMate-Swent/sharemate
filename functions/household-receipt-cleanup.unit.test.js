// Co-authored-by: OpenAI Codex <noreply@openai.com>
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { cleanupHouseholdReceipts } = require("./household-receipt-cleanup");

function fixture(exists = false) {
  const rows = new Map(["receiptBackups", "receipts"].map(name => [name,
    Array.from({ length: 405 }, (_, id) => ({ id, householdId: "gone" }))
      .concat([{ id: "keep", householdId: "other" }])]));
  const deletedPhotos = [];
  const db = {
    collection(name) {
      if (name === "households") return { doc: () => ({ get: async () => ({ exists }) }) };
      return { where(field, operator, value) {
        assert.equal(field, "householdId"); assert.equal(operator, "==");
        return { limit(size) { return { get: async () => {
          const docs = rows.get(name).filter(row => row.householdId === value).slice(0, size)
            .map(row => ({ ref: { name, row } }));
          return { empty: docs.length === 0, docs };
        } }; } };
      } };
    },
    batch() {
      const pending = [];
      return { delete: ref => pending.push(ref), commit: async () => {
        pending.forEach(({ name, row }) => rows.set(name, rows.get(name).filter(r => r !== row)));
      } };
    },
  };
  db.collectionGroup = name => db.collection(name);
  return { db, rows, deletedPhotos, bucket: { deleteFiles: async options => deletedPhotos.push(options) } };
}

test("membership departure keeps all household receipts and photos", async () => {
  const f = fixture(true);
  await cleanupHouseholdReceipts(f.db, f.bucket, "gone");
  assert.equal(f.rows.get("receiptBackups").length, 406);
  assert.deepEqual(f.deletedPhotos, []);
});

test("deletion is paginated, scoped and safe to retry", async () => {
  const f = fixture();
  await cleanupHouseholdReceipts(f.db, f.bucket, "gone");
  await cleanupHouseholdReceipts(f.db, f.bucket, "gone");
  for (const rows of f.rows.values()) assert.deepEqual(rows, [{ id: "keep", householdId: "other" }]);
  assert.deepEqual(f.deletedPhotos, Array(2).fill({ prefix: "receiptPhotos/gone/" }));
});

test("storage failures propagate so the trigger retries", async () => {
  const f = fixture();
  await assert.rejects(cleanupHouseholdReceipts(f.db, {
    deleteFiles: async () => { throw Error("unavailable"); },
  }, "gone"), /unavailable/);
});
