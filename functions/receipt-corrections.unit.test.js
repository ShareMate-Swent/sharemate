// Co-authored-by: OpenAI Codex <noreply@openai.com>
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { correctReceipt } = require("./receipt-corrections");

function fixture() {
  const originalRows = [{ id: "milk", name: "Milk", quantity: 2, priceCents: "400" }, { id: "bread", name: "Bread", quantity: 1, priceCents: "200" }];
  const expense = { id: "receipt", householdId: "home", paidBy: "alice", totalAmountCents: "600", shares: { alice: "300", bob: "300" } };
  const decodeRows = rows => rows.map(r => ({ ...r, quantity: BigInt(r.quantity), priceCents: BigInt(r.priceCents) }));
  const decodeExpense = value => ({ ...value, totalAmountCents: BigInt(value.totalAmountCents), shares: Object.fromEntries(Object.entries(value.shares).map(([id, amount]) => [id, BigInt(amount)])) });
  const records = new Map([
    ["households/home", { type: "SHARED", memberIds: ["alice", "bob"] }],
    ["users/alice/inventorySubmissions/receipt", { id: "receipt", ownerId: "alice", householdId: "home", rows: decodeRows(originalRows) }],
    ["households/home/expenses/receipt", decodeExpense(expense)],
    ["users/alice/expenseSubmissions/receipt", { householdId: "home" }],
    ...originalRows.map((row, i) => [`items/receipt_${i}`, { id: `receipt_${i}`, ownerId: "alice", householdId: "home", name: row.name, quantity: BigInt(row.quantity), status: "ACTIVE", sharing: "SHARED", category: "groceries" }]),
  ]);
  const ref = path => ({ path, collection: name => ({ doc: id => ref(`${path}/${name}/${id}`) }) });
  const db = { collection: name => ({ doc: id => ref(`${name}/${id}`) }), runTransaction: async callback => {
    const writes = [];
    const result = await callback({ get: async r => ({ data: () => records.get(r.path) }),
      set: (r, data) => writes.push(() => records.set(r.path, data)),
      create: (r, data) => { assert.equal(records.has(r.path), false); writes.push(() => records.set(r.path, data)); },
      update: (r, data) => writes.push(() => records.set(r.path, { ...records.get(r.path), ...data })),
      delete: r => writes.push(() => records.delete(r.path)) });
    writes.forEach(write => write());
    return result;
  }};
  const request = { operationId: "correction", title: "Courses Migros", originalRows, originalExpense: expense,
    rows: [{ ...originalRows[0], name: "Oat milk", quantity: 3, priceCents: "450" }, { id: "apples", name: "Apples", quantity: 1, priceCents: "150" }],
    expense: { ...expense, paidBy: "bob" } };
  return { db, records, request };
}

test("updates inventory and ledger once and preserves unrelated item metadata", async () => {
  const { db, records, request } = fixture();
  assert.deepEqual(await correctReceipt(db, "alice", request), { operationId: "correction" });
  assert.equal(records.get("items/receipt_0").name, "Oat milk");
  assert.equal(records.get("items/receipt_0").quantity, 3n);
  assert.equal(records.get("items/receipt_0").category, "groceries");
  assert.equal(records.has("items/receipt_1"), false);
  assert.equal(records.get("items/receipt_edit_correction_1").name, "Apples");
  assert.equal(records.get("households/home/expenses/receipt").paidBy, "bob");
  const size = records.size;
  await correctReceipt(db, "alice", request);
  assert.equal(records.size, size);
  await assert.rejects(correctReceipt(db, "alice", { ...request, title: "different" }), { code: "already-exists" });
});

test("current household member edits another author's saved receipt without changing ownership", async () => {
  const { db, records, request } = fixture();
  records.set('receiptBackups/receipt', { ownerId: 'alice', householdId: 'home', draft: false, photoReady: true });
  await correctReceipt(db, "bob", { ...request, ownerId: "alice" });
  assert.equal(records.get("users/alice/inventorySubmissions/receipt").ownerId, "alice");
  assert.equal(records.get("items/receipt_edit_correction_1").ownerId, "alice");
  assert.equal(records.has("users/bob/inventorySubmissions/receipt"), false);
  const backup = records.get('receiptBackups/receipt');
  assert.equal(backup.title, request.title);
  assert.equal(backup.photoReady, true);
  const snapshot = JSON.parse(backup.submission);
  assert.equal(snapshot.ownerId, 'alice');
  assert.equal(snapshot.expenseProgress, 'CONFIRMED');
  assert.equal(snapshot.rows[0].name, 'Oat milk');
  records.get('households/home').memberIds = ['alice'];
  await assert.rejects(correctReceipt(db, 'bob', { ...request, ownerId: 'alice' }), { code: 'permission-denied' });
  const before = structuredClone(records);
  await assert.rejects(correctReceipt(db, "outsider", { ...request, ownerId: "alice" }));
  assert.deepEqual(records, before);
});

test("rejects outsiders, forged original rows and participants without partial writes", async () => {
  for (const alter of [
    r => ({ ...r, originalRows: r.originalRows.map(row => ({ ...row, name: "forged" })) }),
    r => ({ ...r, expense: { ...r.expense, paidBy: "outsider" } }),
    r => ({ ...r, expense: { ...r.expense, shares: { outsider: "600" } } }),
    r => ({ ...r, expense: { ...r.expense, totalAmountCents: "601" } }),
  ]) {
    const { db, records, request } = fixture();
    const before = structuredClone(records);
    await assert.rejects(correctReceipt(db, "alice", alter(request)));
    assert.deepEqual(records, before);
  }
  const { db, request } = fixture();
  await assert.rejects(correctReceipt(db, null, request), { code: "unauthenticated" });
  await assert.rejects(correctReceipt(db, "outsider", request), { code: "permission-denied" });
  await assert.rejects(correctReceipt(db, "bob", request), { code: "failed-precondition" });
});

test("keeps consumed quantities and resolves an inventory-confirmed purchase with no expense yet", async () => {
  const { db, records, request } = fixture();
  records.get("items/receipt_0").quantity = 1n;
  records.delete("households/home/expenses/receipt");
  records.delete("users/alice/expenseSubmissions/receipt");
  await correctReceipt(db, "alice", request);
  assert.equal(records.get("items/receipt_0").quantity, 2n);
  assert.equal(records.get("households/home/expenses/receipt").totalAmountCents, 600n);
});

test("detects stale edits and never recreates a consumed or removed item", async () => {
  const { db, records, request } = fixture();
  records.delete("items/receipt_0");
  const before = structuredClone(records);
  await assert.rejects(correctReceipt(db, "alice", request), { code: "failed-precondition" });
  assert.deepEqual(records, before);
  const priceOnly = { ...request, rows: request.originalRows, expense: request.originalExpense };
  await correctReceipt(db, "alice", priceOnly);
  assert.equal(records.has("items/receipt_0"), false);
});
