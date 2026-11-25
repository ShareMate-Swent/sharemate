// Co-authored-by: OpenAI Codex <noreply@openai.com>
const { onCall } = require("firebase-functions/v2/https");

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
