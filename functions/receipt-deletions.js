// Co-authored-by: OpenAI Codex <noreply@openai.com>
const { HttpsError } = require('firebase-functions/v2/https');
const { isId } = require('./finance-common');

/** Delete only the authenticated owner's receipt and its explicitly linked records. */
async function deleteReceipt(db, uid, request, deletePhoto = async () => {}) {
  if (!uid) throw new HttpsError('unauthenticated', 'Sign in first.');
  if (!request || Object.keys(request).sort().join() !== 'householdId,id' ||
      !isId(request.id) || !isId(request.householdId)) {
    throw new HttpsError('invalid-argument', 'Invalid receipt.');
  }
  const user = db.collection('users').doc(uid);
  const home = db.collection('households').doc(request.householdId);
  const marker = user.collection('inventorySubmissions').doc(request.id);
  const links = user.collection('receiptItemLinks').doc(request.id);
  const expenseMarker = user.collection('expenseSubmissions').doc(request.id);
  const expense = home.collection('expenses').doc(request.id);
  const tombstone = user.collection('receiptDeletions').doc(request.id);
  const backupRef = user.collection('receiptBackups').doc(request.id);
  await db.runTransaction(async tx => {
    const prior = (await tx.get(tombstone)).data();
    if (prior) {
      if (prior.householdId !== request.householdId)
        throw new HttpsError('permission-denied', 'Receipt ownership mismatch.');
      return;
    }
    const household = (await tx.get(home)).data();
    if (!household?.memberIds?.includes(uid))
      throw new HttpsError('permission-denied', 'Household membership required.');
    const original = (await tx.get(marker)).data();
    const ownership = (await tx.get(expenseMarker)).data();
    const ledger = (await tx.get(expense)).data();
    const mapping = (await tx.get(links)).data()?.links;
    const backup = (await tx.get(backupRef)).data();
    if (backup && (backup.ownerId !== uid || backup.householdId !== request.householdId))
      throw new HttpsError('permission-denied', 'Receipt ownership mismatch.');
    // Local-only previews have no server records. Never touch another owner's ledger.
    if (!original && !ownership && !ledger && !mapping) {
      tx.delete(backupRef);
      tx.create(tombstone, { householdId: request.householdId });
      return;
    }
    if (!original || original.ownerId !== uid || original.householdId !== request.householdId ||
        original.id !== request.id || !Array.isArray(original.rows) || original.rows.length > 100 ||
        (ledger && ownership?.householdId !== request.householdId))
      throw new HttpsError('failed-precondition', 'Receipt is unavailable or still syncing.');
    const items = [];
    for (const [index, row] of original.rows.entries()) {
      const itemId = mapping && Object.hasOwn(mapping, row.id) ? mapping[row.id] : `${request.id}_${index}`;
      if (!isId(itemId)) throw new HttpsError('failed-precondition', 'Invalid receipt link.');
      const ref = db.collection('items').doc(itemId);
      const item = (await tx.get(ref)).data();
      if (item && (item.ownerId !== uid || item.householdId !== request.householdId))
        throw new HttpsError('permission-denied', 'Item ownership changed.');
      if (item) items.push(ref);
    }
    items.forEach(ref => tx.delete(ref));
    tx.delete(expense);
    tx.delete(expenseMarker);
    tx.delete(links);
    tx.delete(marker);
    tx.delete(backupRef);
    tx.set(home.collection('financeMetadata').doc('settlementRevision'), { lastReceiptDeletion: request.id });
    tx.create(tombstone, { householdId: request.householdId });
  });
  await deletePhoto(`receiptPhotos/${request.householdId}/${uid}/${request.id}.jpg`);
  return { id: request.id };
}
module.exports = { deleteReceipt };
