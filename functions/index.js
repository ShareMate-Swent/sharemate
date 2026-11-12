// Co-authored-by: OpenAI Codex <noreply@openai.com>
const { onCall } = require("firebase-functions/v2/https");

const SETTLEMENT_REGION = "europe-west6";

let db;
exports.recordSettlement = onCall({ region: SETTLEMENT_REGION }, (request) => {
  if (!db) {
    const { initializeApp } = require("firebase-admin/app");
    const { getFirestore } = require("firebase-admin/firestore");
    db = getFirestore(initializeApp());
    db.settings({ useBigInt: true });
  }
  const { recordSettlement } = require("./settlements");
  return recordSettlement(db, request.auth?.uid, request.data);
});
