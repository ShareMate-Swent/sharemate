// Co-authored-by: OpenAI Codex <noreply@openai.com>
const { onCall } = require("firebase-functions/v2/https");
const { onDocumentDeleted } = require("firebase-functions/v2/firestore");

const SETTLEMENT_REGION = "europe-west6";

let db;
function database() {
  if (!db) {
    const { initializeApp } = require("firebase-admin/app");
    const { getFirestore } = require("firebase-admin/firestore");
    db = getFirestore(initializeApp());
    db.settings({ useBigInt: true });
  }
  return db;
}

exports.recordSettlement = onCall({ region: SETTLEMENT_REGION }, (request) => {
  const { recordSettlement } = require("./settlements");
  return recordSettlement(database(), request.auth?.uid, request.data);
});

exports.recordExpense = onCall({ region: SETTLEMENT_REGION }, (request) => {
  const { recordExpense } = require("./expenses");
  return recordExpense(database(), request.auth?.uid, request.data);
});

exports.correctReceipt = onCall({ region: SETTLEMENT_REGION }, (request) => {
  return require("./receipt-corrections").correctReceipt(database(), request.auth?.uid, request.data);
});
exports.deleteReceipt = onCall({ region: SETTLEMENT_REGION }, (request) => {
  const firestore = database();
  const { getStorage } = require('firebase-admin/storage');
  return require("./receipt-deletions").deleteReceipt(firestore, request.auth?.uid, request.data,
    path => getStorage().bucket().file(path).delete({ ignoreNotFound: true }));
});

exports.cleanupDeletedHouseholdReceipts = onDocumentDeleted(
  { document: "households/{householdId}", region: SETTLEMENT_REGION, retry: true },
  async event => {
    const firestore = database();
    const { getStorage } = require("firebase-admin/storage");
    await require("./household-receipt-cleanup").cleanupHouseholdReceipts(
      firestore, getStorage().bucket(), event.params.householdId);
  });
