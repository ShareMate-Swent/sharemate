// Co-authored-by: OpenAI Codex <noreply@openai.com>
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { reserveRecipeRequest, recipeBudgetDocument } = require("./recipe-rate-limit");
const start = Date.parse("2026-11-26T12:00:00Z");

function fixture() {
  const records = new Map();
  const db = {
    collection(name) { assert.equal(name, "recipeRequestBudgets"); return { doc: id => ({ id }) }; },
    async runTransaction(run) {
      const writes = [];
      await run({
        get: async ref => ({ exists: records.has(ref.id), data: () => records.get(ref.id) }),
        set: (ref, value) => writes.push([ref.id, value]),
      });
      writes.forEach(([id, value]) => records.set(id, value));
    },
  };
  return { db, records, record: uid => records.get(recipeBudgetDocument(db, uid).id) };
}

test("first request reserves once and exact 30-second boundary is accepted", async () => {
  const f = fixture();
  await reserveRecipeRequest(f.db, "alice", () => start);
  assert.deepEqual(f.record("alice"), { day: "2026-11-26", count: 1, lastAcceptedAt: start });
  for (const delta of [0, 29999])
    await assert.rejects(reserveRecipeRequest(f.db, "alice", () => start + delta), { code: "resource-exhausted" });
  assert.equal(f.record("alice").count, 1);
  await reserveRecipeRequest(f.db, "alice", () => start + 30000);
  assert.equal(f.record("alice").count, 2);
});

test("exactly ten daily requests are allowed and other UIDs have separate budgets", async () => {
  const f = fixture();
  for (let i = 0; i < 10; i++) await reserveRecipeRequest(f.db, "alice", () => start + i * 30000);
  await assert.rejects(reserveRecipeRequest(f.db, "alice", () => start + 10 * 30000), { code: "resource-exhausted" });
  await reserveRecipeRequest(f.db, "bob", () => start);
  assert.equal(f.record("alice").count, 10);
  assert.equal(f.record("bob").count, 1);
});

test("UTC calendar rollover resets allowance while preserving the cross-midnight cooldown", async () => {
  const f = fixture();
  const last = Date.parse("2026-11-26T23:59:50Z");
  for (let i = 0; i < 10; i++) await reserveRecipeRequest(f.db, "alice", () => last - (9 - i) * 30000);
  await assert.rejects(reserveRecipeRequest(f.db, "alice", () => Date.parse("2026-11-27T00:00:00Z")), { code: "resource-exhausted" });
  await reserveRecipeRequest(f.db, "alice", () => Date.parse("2026-11-27T00:00:20Z"));
  assert.deepEqual(f.record("alice"), { day: "2026-11-27", count: 1, lastAcceptedAt: last + 30000 });
});

test("clock rollback cannot reset a previously reserved day", async () => {
  const f = fixture();
  await reserveRecipeRequest(f.db, "alice", () => start);
  await assert.rejects(reserveRecipeRequest(f.db, "alice", () => start - 86400000), { code: "resource-exhausted" });
  assert.equal(f.record("alice").count, 1);
});

test("corrupt stored state fails closed and storage errors stay safe", async () => {
  for (const record of [{}, { day: "bad", count: 1, lastAcceptedAt: start },
    { day: "2026-11-26", count: -1, lastAcceptedAt: start },
    { day: "2026-11-26", count: 11, lastAcceptedAt: start }]) {
    const f = fixture(); f.records.set(recipeBudgetDocument(f.db, "alice").id, record);
    await assert.rejects(reserveRecipeRequest(f.db, "alice", () => start), { code: "internal" });
  }
  const f = fixture();
  f.db.runTransaction = async () => { throw Error("private-storage-details"); };
  await assert.rejects(reserveRecipeRequest(f.db, "alice", () => start), error =>
    error.code === "internal" && !JSON.stringify(error).includes("private-storage-details"));
});

test("transaction retries reevaluate the trusted clock and reserve only after commit", async () => {
  const f = fixture();
  let clockReads = 0;
  const commit = f.db.runTransaction;
  f.db.runTransaction = async run => {
    await run({ get: async () => ({ exists: false }), set: () => {} });
    await commit(run);
  };
  await reserveRecipeRequest(f.db, "alice", () => { clockReads++; return start + clockReads * 30000; });
  assert.equal(clockReads, 2);
  assert.equal(f.record("alice").count, 1);
  assert.equal(f.record("alice").lastAcceptedAt, start + 60000);
});
