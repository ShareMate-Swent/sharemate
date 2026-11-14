// Co-authored-by: OpenAI Codex <noreply@openai.com>
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { recordExpense } = require("./expenses");

// Deterministic transaction double; actual Firestore contention/auth are emulator tests.
function fixture() {
  const records = new Map([["households/home", { type: "SHARED", memberIds: ["author", "payer"] }]]);
  const reference = (path) => ({ path,
    collection: (name) => ({ doc: (id) => reference(`${path}/${name}/${id}`) }) });
  const db = {
    collection: (name) => ({ doc: (id) => reference(`${name}/${id}`) }),
    runTransaction: async (run) => {
      const writes = [];
      const result = await run({
        get: async (ref) => ({ data: () => records.get(ref.path) }),
        create: (ref, data) => {
          assert.equal(records.has(ref.path), false);
          writes.push([ref.path, data]);
        },
        set: (ref, data) => writes.push([ref.path, data]),
      });
      for (const [path, data] of writes) records.set(path, data);
      return result;
    },
  };
  const payload = { id: "operation", householdId: "home", paidBy: "payer",
    totalAmountCents: "1000", shares: { author: "500", payer: "500" } };
  return { db, records, payload };
}

test("records only the canonical ledger fields and returns the original wire payload", async () => {
  const { db, records, payload } = fixture();
  assert.equal(await recordExpense(db, "author", payload), payload);
  assert.deepEqual(records.get("households/home/expenses/operation"), {
    ...payload, totalAmountCents: 1000n, shares: { author: 500n, payer: 500n },
  });
  assert.deepEqual(records.get("users/author/expenseSubmissions/operation"), { householdId: "home" });
  assert.deepEqual(records.get("households/home/financeMetadata/settlementRevision"), { lastExpenseId: "operation" });
  const size = records.size;
  await recordExpense(db, "author", payload);
  assert.equal(records.size, size);
  await assert.rejects(recordExpense(db, "author", { ...payload, paidBy: "author" }), { code: "already-exists" });
  await assert.rejects(recordExpense(db, "payer", payload), { code: "already-exists" });
});

test("rejects malformed payloads and does not write on validation failure", async () => {
  const { db, records, payload } = fixture();
  const cases = [null, [], {}, { ...payload, extra: "forged" },
    ...["", " ", ".", "..", "a/b", "__reserved__", "x".repeat(1501)].map((id) => ({ ...payload, id })),
    ...[null, 1000, "0", "-1", "1.0", "01", "9223372036854775808"].map((totalAmountCents) =>
      ({ ...payload, totalAmountCents })),
    ...[null, [], {}, { author: "999" }, { author: "1001" }, { author: "-1" },
      { author: 1000 }, { "bad/id": "1000" }, { author: "9223372036854775808" }].map((shares) =>
      ({ ...payload, shares })),
  ];
  for (const request of cases) {
    await assert.rejects(recordExpense(db, "author", request), { code: "invalid-argument" });
  }
  assert.equal(records.size, 1);
  await assert.rejects(recordExpense(db, null, payload), { code: "unauthenticated" });
});

test("requires current membership on creation, replay and across households", async () => {
  const { db, records, payload } = fixture();
  await assert.rejects(recordExpense(db, "outsider", payload), { code: "permission-denied" });
  await assert.rejects(recordExpense(db, "author", { ...payload, paidBy: "outsider" }), { code: "permission-denied" });
  await assert.rejects(recordExpense(db, "author", { ...payload, shares: { outsider: "1000" } }), { code: "permission-denied" });
  await recordExpense(db, "author", payload);
  records.set("households/other", { type: "SHARED", memberIds: ["author", "payer"] });
  await assert.rejects(recordExpense(db, "author", { ...payload, householdId: "other" }), { code: "already-exists" });
  records.set("households/home", { type: "SHARED", memberIds: ["author"] });
  await recordExpense(db, "author", payload);
  await assert.rejects(recordExpense(db, "author", { ...payload, id: "new" }), { code: "permission-denied" });
  for (const parent of [undefined, { type: "PRIVATE", memberIds: ["author"] },
    { type: "SHARED", memberIds: "author" }, { type: "SHARED", memberIds: ["payer"] }]) {
    records.set("households/home", parent);
    await assert.rejects(recordExpense(db, "author", payload), { code: "permission-denied" });
  }
});

test("preserves Long.MAX_VALUE and zero shares without floating point or prototype keys", async () => {
  const { db, records, payload } = fixture();
  const maximum = { ...payload, totalAmountCents: "9223372036854775807",
    shares: { author: "9223372036854775807", payer: "0" } };
  await recordExpense(db, "author", maximum);
  assert.equal(records.get("households/home/expenses/operation").totalAmountCents, 9223372036854775807n);
  const special = { ...payload, id: "special", paidBy: "constructor", totalAmountCents: "1",
    shares: JSON.parse('{"constructor":"1"}') };
  records.set("households/home", { type: "SHARED", memberIds: ["author", "constructor"] });
  await recordExpense(db, "author", special);
  assert.equal(Object.getOwnPropertyDescriptor(records.get("households/home/expenses/special").shares,
    "constructor").value, 1n);
});

test("propagates uncertain transaction failures without translating them to rejection", async () => {
  const { db, payload } = fixture();
  const error = new Error("Lost commit response");
  db.runTransaction = async () => { throw error; };
  await assert.rejects(recordExpense(db, "author", payload), (failure) => failure === error);
});

test("does not acknowledge orphaned or corrupted retry records", async () => {
  for (const corruption of ["missing-expense", "wrong-household", "extra-field", "wrong-share"]) {
    const { db, records, payload } = fixture();
    await recordExpense(db, "author", payload);
    if (corruption === "missing-expense") records.delete("households/home/expenses/operation");
    if (corruption === "wrong-household") records.set("users/author/expenseSubmissions/operation", { householdId: "other" });
    const saved = records.get("households/home/expenses/operation");
    if (corruption === "extra-field") saved.extra = "invalid";
    if (corruption === "wrong-share") records.set("households/home/expenses/operation", { ...saved, shares: { author: 1000n } });
    await assert.rejects(recordExpense(db, "author", payload), { code: "already-exists" });
  }
});

test("propagates a transaction read failure before writing", async () => {
  const { db, records, payload } = fixture();
  const error = new Error("Read failed");
  db.runTransaction = async (run) => run({ get: async () => { throw error; } });
  await assert.rejects(recordExpense(db, "author", payload), (failure) => failure === error);
  assert.equal(records.size, 1);
});
