// Co-authored-by: OpenAI Codex <noreply@openai.com>
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { deleteReceipt } = require('./receipt-deletions');
function fixture() {
  const records = new Map([
    ['households/home', { memberIds: ['alice', 'bob'] }],
    ['users/alice/inventorySubmissions/r', { id: 'r', ownerId: 'alice', householdId: 'home', rows: [{ id: 'milk' }] }],
    ['users/alice/expenseSubmissions/r', { householdId: 'home' }],
    ['households/home/expenses/r', { totalAmountCents: 400n }],
    ['items/r_0', { ownerId: 'alice', householdId: 'home' }],
    ['items/unrelated', { ownerId: 'alice', householdId: 'home' }],
  ]);
  const ref = path => ({ path, collection: name => ({ doc: id => ref(`${path}/${name}/${id}`) }) });
  const db = { collection: name => ({ doc: id => ref(`${name}/${id}`) }), runTransaction: async callback => {
    const writes = [];
    await callback({ get: async r => ({ data: () => records.get(r.path) }),
      delete: r => writes.push(() => records.delete(r.path)),
      set: (r, value) => writes.push(() => records.set(r.path, value)),
      create: (r, value) => writes.push(() => records.set(r.path, value)) });
    writes.forEach(write => write());
  }};
  return { db, records };
}
test('deletes linked data atomically and accepts retries', async () => {
  const { db, records } = fixture();
  await deleteReceipt(db, 'alice', { id: 'r', householdId: 'home' });
  assert.equal(records.has('items/r_0'), false);
  assert.equal(records.has('households/home/expenses/r'), false);
  assert.equal(records.has('users/alice/inventorySubmissions/r'), false);
  assert.equal(records.has('items/unrelated'), true);
  const after = structuredClone(records);
  await deleteReceipt(db, 'alice', { id: 'r', householdId: 'home' });
  assert.deepEqual(records, after);
});
test('rejects other owners and changed item ownership without partial deletion', async () => {
  const { db, records } = fixture();
  const before = structuredClone(records);
  await assert.rejects(deleteReceipt(db, 'bob', { id: 'r', householdId: 'home' }));
  assert.deepEqual(records, before);
  records.set('items/r_0', { ownerId: 'bob', householdId: 'home' });
  await assert.rejects(deleteReceipt(db, 'alice', { id: 'r', householdId: 'home' }));
  assert.equal(records.has('households/home/expenses/r'), true);
});
test('requires authentication and valid identifiers', async () => {
  const { db } = fixture();
  await assert.rejects(deleteReceipt(db, null, { id: 'r', householdId: 'home' }));
  await assert.rejects(deleteReceipt(db, 'alice', { id: '../r', householdId: 'home' }));
});

test('photo cleanup retries after an atomic deletion, including private draft backups', async () => {
  const { db, records } = fixture();
  records.set('users/alice/receiptBackups/draft', { ownerId: 'alice', householdId: 'home', draft: true });
  await assert.rejects(deleteReceipt(db, 'alice', { id: 'draft', householdId: 'home' }, async () => { throw Error('Network'); }));
  assert.equal(records.has('users/alice/receiptBackups/draft'), false);
  assert.equal(records.has('users/alice/receiptDeletions/draft'), true);
  let photo;
  await deleteReceipt(db, 'alice', { id: 'draft', householdId: 'home' }, async path => { photo = path; });
  assert.equal(photo, 'receiptPhotos/home/alice/draft.jpg');
  assert.equal(records.has('households/home/expenses/r'), true);
});
