// Co-authored-by: OpenAI Codex <noreply@openai.com>
const DELETE_BATCH_SIZE = 400;

/** Retryable cleanup of receipt documents and photos after their household is deleted. */
async function cleanupHouseholdReceipts(db, bucket, householdId) {
  if ((await db.collection("households").doc(householdId).get()).exists) return;
  for (const collection of ["receiptBackups", "receipts"]) {
    const query = db.collection(collection).where("householdId", "==", householdId)
      .limit(DELETE_BATCH_SIZE);
    for (;;) {
      const snapshot = await query.get();
      if (snapshot.empty) break;
      const batch = db.batch();
      snapshot.docs.forEach(doc => batch.delete(doc.ref));
      await batch.commit();
    }
  }
  await bucket.deleteFiles({ prefix: `receiptPhotos/${householdId}/` });
}

module.exports = { cleanupHouseholdReceipts };
