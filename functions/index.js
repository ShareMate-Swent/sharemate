// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
const { onCall } = require("firebase-functions/v2/https");

const SETTLEMENT_REGION = "europe-west6";
/** Deleting a large household can take a while; the default 60 seconds is too short. */
const DELETE_HOUSEHOLD_TIMEOUT_SECONDS = 300;

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

let storageBucket;
function bucket() {
  if (!storageBucket) {
    const { getStorage } = require("firebase-admin/storage");
    database();
    storageBucket = getStorage().bucket();
  }
  return storageBucket;
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

exports.deletePrivateHousehold = onCall(
  { region: SETTLEMENT_REGION, timeoutSeconds: DELETE_HOUSEHOLD_TIMEOUT_SECONDS },
  (request) => {
    const { deletePrivateHousehold } = require("./private-household");
    return deletePrivateHousehold(database(), bucket(), request.auth?.uid, request.data);
  });
