// Co-authored-by: OpenAI Codex <noreply@openai.com>
const { HttpsError } = require("firebase-functions/v2/https");
const { createHash } = require("node:crypto");
const { parseExpense, sameExpense } = require("./expenses");
const { isId } = require("./finance-common");

function rows(input) {
  if (!Array.isArray(input) || input.length < 1 || input.length > 100) return null;
  const ids = new Set();
  const result = [];
  for (const row of input) {
    if (!row || Object.keys(row).sort().join() !== "id,name,priceCents,quantity" ||
        !isId(row.id) || row.id.length > 1000 || ids.has(row.id) ||
        typeof row.name !== "string" || !row.name.trim() || [...row.name].length > 80 ||
        !Number.isInteger(row.quantity) || row.quantity < 1 || row.quantity > 9999 ||
        (row.priceCents !== null && (typeof row.priceCents !== "string" || !/^(0|[1-9][0-9]{0,8})$/.test(row.priceCents)))) return null;
    ids.add(row.id);
    result.push({ ...row, quantity: BigInt(row.quantity), priceCents: row.priceCents === null ? null : BigInt(row.priceCents) });
  }
  return result;
}
const sameRows = (a, b) => Array.isArray(a) && a.length === b.length && a.every((r, i) =>
  Object.keys(r).sort().join() === "id,name,priceCents,quantity" &&
  r.id === b[i].id && r.name === b[i].name && r.quantity === b[i].quantity && r.priceCents === b[i].priceCents);
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === "object"
  ? Object.fromEntries(Object.keys(value).sort().map(k => [k, stable(value[k])])) : value;

async function correctReceipt(db, uid, request) {
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first.");
  const ownerId = request?.ownerId ?? uid;
  if (!isId(ownerId) || !request || Object.keys(request).filter(k => k !== 'ownerId').sort().join() !== "expense,operationId,originalExpense,originalRows,originalTitle,rows,title" ||
      !isId(request.operationId) || request.operationId.length > 128 || typeof request.title !== "string" || [...request.title].length > 80 ||
      typeof request.originalTitle !== "string" || [...request.originalTitle].length > 80)
    throw new HttpsError("invalid-argument", "Invalid correction.");
  const expense = parseExpense(request.expense);
  const originalExpense = request.originalExpense === null ? null : parseExpense(request.originalExpense);
  const original = rows(request.originalRows), revised = rows(request.rows);
  if (!expense || expense.id.length > 128 || !original || !revised || (request.originalExpense !== null && !originalExpense) ||
      (originalExpense && (originalExpense.id !== expense.id || originalExpense.householdId !== expense.householdId)) ||
      revised.some(r => r.priceCents === null) || revised.reduce((total, r) => total + r.priceCents, 0n) !== expense.totalAmountCents)
    throw new HttpsError("invalid-argument", "Invalid correction.");
  const hash = createHash("sha256").update(JSON.stringify(stable(request))).digest("hex");
  const home = db.collection("households").doc(expense.householdId);
  const user = db.collection("users").doc(ownerId);
  const operation = db.collection("users").doc(uid).collection("receiptCorrections").doc(request.operationId);
  const marker = user.collection("inventorySubmissions").doc(expense.id);
  const linksRef = user.collection("receiptItemLinks").doc(expense.id);
  const expenseRef = home.collection("expenses").doc(expense.id);
  const expenseMarker = user.collection("expenseSubmissions").doc(expense.id);
  const revision = home.collection("financeMetadata").doc("settlementRevision");
  const backupRef = user.collection('receiptBackups').doc(expense.id);
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
      if (![expense.paidBy, ...Object.keys(expense.shares)].every(id => household.memberIds.includes(id)))
        throw new HttpsError("permission-denied", "Select current household members.");
      const current = (await tx.get(marker)).data();
      const ledger = (await tx.get(expenseRef)).data();
      const ownership = (await tx.get(expenseMarker)).data();
      if (!current || current.ownerId !== ownerId || current.householdId !== expense.householdId || current.id !== expense.id ||
          (ownerId !== uid && (!ledger || !ownership)) ||
          !sameRows(current.rows, original) ||
          (ledger && (!originalExpense || !sameExpense(ledger, originalExpense) || ownership?.householdId !== expense.householdId)) ||
          (!ledger && ownership)) throw new HttpsError("failed-precondition", "Receipt changed or is still syncing. Refresh and retry.");
      const storedReceipt = (await tx.get(linksRef)).data();
      const storedLinks = storedReceipt?.links;
      const oldLinks = new Map(original.map((row, i) => [row.id, storedLinks && Object.hasOwn(storedLinks, row.id) ? storedLinks[row.id] : `${expense.id}_${i}`]));
      const existingItems = new Map();
      for (const [rowId, itemId] of oldLinks) {
        const ref = db.collection("items").doc(itemId);
        existingItems.set(rowId, { ref, data: (await tx.get(ref)).data() });
      }
      await tx.get(revision);
      const backup = (await tx.get(backupRef)).data();
      if (backup && (backup.ownerId !== ownerId || backup.householdId !== expense.householdId))
        throw new HttpsError('permission-denied', 'Receipt ownership mismatch.');
      if ((storedReceipt?.title ?? backup?.title ?? "") !== request.originalTitle)
        throw new HttpsError("failed-precondition", "Receipt title changed. Refresh and retry.");
      const authorizeItem = item => {
        if (item && item.ownerId !== uid && item.sharing !== "SHARED")
          throw new HttpsError("permission-denied", "Only the owner can modify a personal item.");
      };
      const writes = [], nextLinks = {};
      for (let i = 0; i < revised.length; i++) {
        const row = revised[i], before = original.find(r => r.id === row.id), stored = existingItems.get(row.id);
        const itemId = oldLinks.get(row.id) ?? `${expense.id}_edit_${request.operationId}_${i}`;
        Object.defineProperty(nextLinks, row.id, { value: itemId, enumerable: true });
        if (!before) {
          writes.push(() => tx.create(db.collection("items").doc(itemId), { id: itemId, name: row.name, quantity: row.quantity,
            ownerId: ownerId, householdId: expense.householdId, sharing: "SHARED", status: "ACTIVE", expirationDate: null, category: null, image: null }));
        } else if (row.name !== before.name || row.quantity !== before.quantity) {
          authorizeItem(stored.data);
          if (!stored.data || stored.data.ownerId !== ownerId || stored.data.householdId !== expense.householdId || stored.data.status !== "ACTIVE")
            throw new HttpsError("failed-precondition", "This fridge item is no longer editable.");
          const quantity = stored.data.quantity + row.quantity - before.quantity;
          if (quantity < 1n || quantity > 9999n) throw new HttpsError("failed-precondition", "Fridge quantity changed. Review it first.");
          writes.push(() => tx.update(stored.ref, { quantity, ...(row.name !== before.name && { name: row.name }) }));
        }
      }
      for (const row of original.filter(r => !revised.some(next => next.id === r.id))) {
        const stored = existingItems.get(row.id);
        if (stored.data && (stored.data.ownerId !== ownerId || stored.data.householdId !== expense.householdId))
          throw new HttpsError("permission-denied", "Fridge item ownership changed.");
        authorizeItem(stored.data);
        if (stored.data) writes.push(() => tx.delete(stored.ref));
      }
      writes.forEach(write => write());
      tx.set(marker, { id: expense.id, ownerId: ownerId, householdId: expense.householdId, rows: revised });
      tx.set(linksRef, { links: nextLinks, title: request.title });
      tx.set(expenseRef, expense);
      tx.set(expenseMarker, { householdId: expense.householdId });
      if (backup) {
        const submission = { id: expense.id, ownerId, householdId: expense.householdId, rows: revised,
          draft: false, expense: { paidBy: expense.paidBy, totalAmountCents: expense.totalAmountCents, shares: expense.shares },
          inventoryConfirmed: true, expenseProgress: 'CONFIRMED' };
        tx.set(backupRef, { ...backup, title: request.title,
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
