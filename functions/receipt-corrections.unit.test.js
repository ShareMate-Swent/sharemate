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
  const request = { operationId: "correction", title: "Courses Migros", originalTitle: "", originalRows, originalExpense: expense,
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
  records.set('users/alice/receiptBackups/receipt', { ownerId: 'alice', householdId: 'home', draft: false, photoReady: true });
  await correctReceipt(db, "bob", { ...request, ownerId: "alice" });
  assert.equal(records.get("users/alice/inventorySubmissions/receipt").ownerId, "alice");
  assert.equal(records.get("items/receipt_edit_correction_1").ownerId, "alice");
  assert.equal(records.has("users/bob/inventorySubmissions/receipt"), false);
  const backup = records.get('users/alice/receiptBackups/receipt');
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

test("preserves the row cost-sharing choice in the inventory marker and receipt backup", async () => {
  const { db, records, request } = fixture();
  const marker = "users/alice/inventorySubmissions/receipt";
  const backup = "users/alice/receiptBackups/receipt";
  records.set(backup, { ownerId: "alice", householdId: "home", draft: false });
  const edit = {
    ...request,
    originalRows: request.originalRows.map(row => ({ ...row, includedInExpense: true })),
    rows: request.rows.map((row, i) => ({ ...row, includedInExpense: i !== 0 })),
  };
  await correctReceipt(db, "alice", edit);
  await correctReceipt(db, "alice", edit);
  assert.equal(records.get(marker).rows[0].includedInExpense, false);
  assert.equal(Object.hasOwn(records.get(marker).rows[1], "includedInExpense"), false);
  assert.equal(JSON.parse(records.get(backup).submission).rows[0].includedInExpense, false);
  assert.equal(records.get("items/receipt_0").sharing, "SHARED");

  const followUp = {
    ...edit, operationId: "next", originalRevision: 1, originalTitle: edit.title,
    originalRows: edit.rows, originalExpense: edit.expense,
  };
  const before = structuredClone(records);
  await assert.rejects(correctReceipt(db, "alice", {
    ...followUp,
    originalRows: edit.rows.map(({ includedInExpense, ...row }) => row),
  }), { code: "failed-precondition" });
  await assert.rejects(correctReceipt(db, "alice", {
    ...followUp,
    rows: edit.rows.map(row => ({ ...row, includedInExpense: "false" })),
  }), { code: "invalid-argument" });
  assert.deepEqual(records, before);
  await correctReceipt(db, "alice", followUp);
  assert.equal(records.get(marker).rows[0].includedInExpense, false);
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


test("peers cannot rename, change quantity or remove a personal item; owners can", async () => {
  for (const mode of ["rename", "quantity", "remove"]) {
    const { db, records, request } = fixture();
    records.get("items/receipt_0").sharing = "PERSONAL";
    const revised = request.originalRows.map(row => ({ ...row }));
    if (mode === "rename") revised[0].name = "Private milk";
    if (mode === "quantity") revised[0].quantity++;
    if (mode === "remove") { revised.shift(); revised[0].priceCents = "600"; }
    const edit = { ...request, ownerId: "alice", rows: revised, expense: request.originalExpense };
    const before = structuredClone(records);
    await assert.rejects(correctReceipt(db, "bob", edit), { code: "permission-denied" });
    assert.deepEqual(records, before);
    await correctReceipt(db, "alice", edit);
    assert.equal(records.has("users/alice/receiptCorrections/correction"), true);
  }
});

test("title-only corrections invalidate stale peer edits but identical retries still succeed", async () => {
  const { db, records, request } = fixture();
  const first = { ...request, rows: request.originalRows, expense: request.originalExpense, title: "New title" };
  await correctReceipt(db, "alice", first);
  await correctReceipt(db, "alice", first);
  const before = structuredClone(records);
  const stale = { ...request, ownerId: "alice", operationId: "peer-edit" };
  await assert.rejects(correctReceipt(db, "bob", stale), { code: "failed-precondition" });
  assert.deepEqual(records, before);
  await correctReceipt(db, "bob", { ...stale, originalTitle: "New title", originalRevision: 1 });
  assert.equal(records.get("users/alice/receiptItemLinks/receipt").title, request.title);
});


test("receipt revisions advance once and reject stale revisions even when content matches", async () => {
  const { db, records, request } = fixture();
  records.set('users/alice/receiptBackups/receipt', { ownerId: 'alice', householdId: 'home', draft: false });
  const unchanged = { ...request, title: '', rows: request.originalRows, expense: request.originalExpense, originalRevision: 0 };
  await correctReceipt(db, 'alice', unchanged);
  assert.equal(records.get('users/alice/receiptItemLinks/receipt').revision, 1);
  assert.equal(records.get('users/alice/receiptBackups/receipt').revision, 1);
  await correctReceipt(db, 'alice', unchanged);
  assert.equal(records.get('users/alice/receiptBackups/receipt').revision, 1);
  await assert.rejects(correctReceipt(db, 'alice', { ...unchanged, operationId: 'stale' }), { code: 'failed-precondition' });
  await correctReceipt(db, 'alice', { ...unchanged, operationId: 'next', originalRevision: 1 });
  assert.equal(records.get('users/alice/receiptBackups/receipt').revision, 2);
  for (const originalRevision of [-1, 0.5, Number.MAX_SAFE_INTEGER, '1']) {
    await assert.rejects(correctReceipt(db, 'alice', { ...unchanged, operationId: 'invalid', originalRevision }), { code: 'invalid-argument' });
  }
});
