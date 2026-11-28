// Co-authored-by: OpenAI Codex <noreply@openai.com>
const { HttpsError } = require("firebase-functions/v2/https");
const { createHash } = require("node:crypto");
const { parseExpense, sameExpense, sharedSubtotal } = require("./expenses");
const { INVENTORY_SUBMISSIONS, INCLUDED_IN_EXPENSE, isId } = require("./finance-common");

const LEGACY_ROW_KEYS = "id,name,priceCents,quantity";
const EXPENSE_ROW_KEYS = `id,${INCLUDED_IN_EXPENSE},name,priceCents,quantity`;
function hasRowFields(row) {
  if (!row) return false;
  const keys = Object.keys(row).sort().join();
  return keys === LEGACY_ROW_KEYS ||
    (keys === EXPENSE_ROW_KEYS && typeof row[INCLUDED_IN_EXPENSE] === "boolean");
}

function rows(input) {
  if (!Array.isArray(input) || input.length < 1 || input.length > 100) return null;
  const ids = new Set();
  const result = [];
  for (const row of input) {
    if (!hasRowFields(row) ||
        !isId(row.id) || row.id.length > 1000 || ids.has(row.id) ||
        typeof row.name !== "string" || !row.name.trim() || [...row.name].length > 80 ||
        !Number.isInteger(row.quantity) || row.quantity < 1 || row.quantity > 9999 ||
        (row.priceCents !== null && (typeof row.priceCents !== "string" || !/^(0|[1-9][0-9]{0,8})$/.test(row.priceCents)))) return null;
    ids.add(row.id);
    const { [INCLUDED_IN_EXPENSE]: includedInExpense, ...fields } = row;
    result.push({
      ...fields, quantity: BigInt(row.quantity), priceCents: row.priceCents === null ? null : BigInt(row.priceCents),
      ...(includedInExpense === false ? { [INCLUDED_IN_EXPENSE]: false } : {}),
    });
  }
  return result;
}
const sameRows = (a, b) => Array.isArray(a) && a.length === b.length && a.every((r, i) =>
  hasRowFields(r) && r.id === b[i].id && r.name === b[i].name &&
  r.quantity === b[i].quantity && r.priceCents === b[i].priceCents &&
  (r[INCLUDED_IN_EXPENSE] ?? true) === (b[i][INCLUDED_IN_EXPENSE] ?? true));
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === "object"
  ? Object.fromEntries(Object.keys(value).sort().map(k => [k, stable(value[k])])) : value;

async function correctReceipt(db, uid, request) {
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first.");
  const ownerId = request?.ownerId ?? uid;
  const inventoryOnly = request?.expense === null;
  const expectedKeys = inventoryOnly
    ? "expense,operationId,originalExpense,originalRows,originalTitle,receipt,rows,title"
    : "expense,operationId,originalExpense,originalRows,originalTitle,rows,title";
  if (!isId(ownerId) || !request || Object.keys(request).filter(k => k !== 'ownerId' && k !== 'originalRevision').sort().join() !== expectedKeys ||
      !isId(request.operationId) || request.operationId.length > 128 || typeof request.title !== "string" || [...request.title].length > 80 ||
      typeof request.originalTitle !== "string" || [...request.originalTitle].length > 80)
    throw new HttpsError("invalid-argument", "Invalid correction.");
  const originalRevision = request.originalRevision ?? 0;
  if (!Number.isSafeInteger(originalRevision) || originalRevision < 0 || originalRevision >= Number.MAX_SAFE_INTEGER)
    throw new HttpsError("invalid-argument", "Invalid receipt revision.");
  const expense = inventoryOnly ? null : parseExpense(request.expense);
  const identity = inventoryOnly ? request.receipt : expense;
  const { id, householdId } = identity ?? {};
  const originalExpense = request.originalExpense === null ? null : parseExpense(request.originalExpense);
  const original = rows(request.originalRows), revised = rows(request.rows);
  if (!isId(id) || id.length > 128 || !isId(householdId) || !original || !revised ||
      (inventoryOnly && Object.keys(identity).sort().join() !== "householdId,id") ||
      (request.originalExpense !== null && !originalExpense) ||
      (originalExpense && (originalExpense.id !== id || originalExpense.householdId !== householdId)) ||
      (inventoryOnly ? revised.some(r => r[INCLUDED_IN_EXPENSE] !== false)
        : sharedSubtotal(revised) !== expense.totalAmountCents))
    throw new HttpsError("invalid-argument", "Invalid correction.");
  const hash = createHash("sha256").update(JSON.stringify(stable(request))).digest("hex");
  const home = db.collection("households").doc(householdId);
  const user = db.collection("users").doc(ownerId);
  const operation = db.collection("users").doc(uid).collection("receiptCorrections").doc(request.operationId);
  const marker = user.collection(INVENTORY_SUBMISSIONS).doc(id);
  const linksRef = user.collection("receiptItemLinks").doc(id);
  const expenseRef = home.collection("expenses").doc(id);
  const expenseMarker = user.collection("expenseSubmissions").doc(id);
  const revision = home.collection("financeMetadata").doc("settlementRevision");
  const backupRef = user.collection('receiptBackups').doc(id);
  const rejection = await db.runTransaction(async tx => {
    try {
      const household = (await tx.get(home)).data();
      if (household?.type !== "SHARED" || !Array.isArray(household.memberIds) || !household.memberIds.includes(uid))
        throw new HttpsError("permission-denied", "Household membership required.");
      const prior = (await tx.get(operation)).data();
      if (prior) {
        if (prior.hash !== hash) throw new HttpsError("already-exists", "Correction identity already used.");
        return;
      }
      if (expense && ![expense.paidBy, ...Object.keys(expense.shares)].every(id => household.memberIds.includes(id)))
        throw new HttpsError("permission-denied", "Select current household members.");
      const current = (await tx.get(marker)).data();
      const ledger = (await tx.get(expenseRef)).data();
      const ownership = (await tx.get(expenseMarker)).data();
      const backup = (await tx.get(backupRef)).data();
      if (backup && (backup.ownerId !== ownerId || backup.householdId !== householdId))
        throw new HttpsError('permission-denied', 'Receipt ownership mismatch.');
      const removedExpense = ownership?.householdId === householdId && ownership.inventoryOnly === true;
      const sharedInventoryOnly = removedExpense && backup?.draft === false &&
        original.every(r => r[INCLUDED_IN_EXPENSE] === false);
      if (!current || current.ownerId !== ownerId || current.householdId !== householdId || current.id !== id ||
          (ownerId !== uid && (!ledger || !ownership) && !sharedInventoryOnly) ||
          !sameRows(current.rows, original) ||
          (ledger && (!originalExpense || !sameExpense(ledger, originalExpense) || ownership?.householdId !== householdId)) ||
          (!ledger && ownership && (!removedExpense || originalExpense)))
        throw new HttpsError("failed-precondition", "Receipt changed or is still syncing. Refresh and retry.");
      if (inventoryOnly && (ledger || originalExpense) && uid !== current.ownerId)
        throw new HttpsError("permission-denied", "Only the receipt owner can remove its shared expense.");
      const storedReceipt = (await tx.get(linksRef)).data();
      const storedLinks = storedReceipt?.links;
      const oldLinks = new Map(original.map((row, i) => [row.id, storedLinks && Object.hasOwn(storedLinks, row.id) ? storedLinks[row.id] : `${id}_${i}`]));
      const existingItems = new Map();
      for (const [rowId, itemId] of oldLinks) {
        const ref = db.collection("items").doc(itemId);
        existingItems.set(rowId, { ref, data: (await tx.get(ref)).data() });
      }
      await tx.get(revision);
      if ((storedReceipt?.title ?? backup?.title ?? "") !== request.originalTitle)
        throw new HttpsError("failed-precondition", "Receipt title changed. Refresh and retry.");
      if (BigInt(storedReceipt?.revision ?? backup?.revision ?? 0) !== BigInt(originalRevision))
        throw new HttpsError("failed-precondition", "Receipt revision changed. Refresh and retry.");
      const nextRevision = originalRevision + 1;
      const authorizeItem = item => {
        if (item && item.ownerId !== uid && item.sharing !== "SHARED")
          throw new HttpsError("permission-denied", "Only the owner can modify a personal item.");
      };
      const writes = [], nextLinks = {};
      for (let i = 0; i < revised.length; i++) {
        const row = revised[i], before = original.find(r => r.id === row.id), stored = existingItems.get(row.id);
        const itemId = oldLinks.get(row.id) ?? `${id}_edit_${request.operationId}_${i}`;
        Object.defineProperty(nextLinks, row.id, { value: itemId, enumerable: true });
        if (!before) {
          writes.push(() => tx.create(db.collection("items").doc(itemId), { id: itemId, name: row.name, quantity: row.quantity,
            ownerId, householdId, sharing: "SHARED", status: "ACTIVE", expirationDate: null, category: null, image: null }));
        } else if (row.name !== before.name || row.quantity !== before.quantity) {
          authorizeItem(stored.data);
          if (!stored.data || stored.data.ownerId !== ownerId || stored.data.householdId !== householdId || stored.data.status !== "ACTIVE")
            throw new HttpsError("failed-precondition", "This fridge item is no longer editable.");
          const quantity = stored.data.quantity + row.quantity - before.quantity;
          if (quantity < 1n || quantity > 9999n) throw new HttpsError("failed-precondition", "Fridge quantity changed. Review it first.");
          writes.push(() => tx.update(stored.ref, { quantity, ...(row.name !== before.name && { name: row.name }) }));
        }
      }
      for (const row of original.filter(r => !revised.some(next => next.id === r.id))) {
        const stored = existingItems.get(row.id);
        if (stored.data && (stored.data.ownerId !== ownerId || stored.data.householdId !== householdId))
          throw new HttpsError("permission-denied", "Fridge item ownership changed.");
        authorizeItem(stored.data);
        if (stored.data) writes.push(() => tx.delete(stored.ref));
      }
      writes.forEach(write => write());
      tx.set(marker, { id, ownerId, householdId, rows: revised });
      tx.set(linksRef, { links: nextLinks, title: request.title, revision: nextRevision });
      if (expense) {
        tx.set(expenseRef, expense);
        tx.set(expenseMarker, { householdId });
      } else if (ledger || originalExpense) {
        if (ledger) tx.delete(expenseRef);
        // Reserve the receipt ID so a delayed expense retry cannot re-create the charge.
        tx.set(expenseMarker, { householdId, inventoryOnly: true });
      }
      if (backup) {
        const submission = { id, ownerId, householdId, rows: revised, draft: false, inventoryConfirmed: true };
        if (expense) {
          const { paidBy, totalAmountCents, shares } = expense;
          submission.expense = { paidBy, totalAmountCents, shares };
          submission.expenseProgress = "CONFIRMED";
        }
        tx.set(backupRef, { ...backup, title: request.title, revision: nextRevision,
          submission: JSON.stringify(submission, (_, value) => typeof value === 'bigint' ? Number(value) : value) });
      }
      tx.set(revision, { lastReceiptCorrection: request.operationId });
      tx.create(operation, { hash });
    } catch (error) { if (error instanceof HttpsError) return error; throw error; }
  });
  if (rejection) throw rejection;
  return { operationId: request.operationId };
}
module.exports = { correctReceipt };
