const { onCall } = require("firebase-functions/v2/https");

let db;
exports.recordSettlement = onCall({ region: "europe-west6" }, (request) => {
  if (!db) {
    const { initializeApp } = require("firebase-admin/app");
    const { getFirestore } = require("firebase-admin/firestore");
    db = getFirestore(initializeApp());
    db.settings({ useBigInt: true });
  }
  const { recordSettlement } = require("./settlements");
  return recordSettlement(db, request.auth?.uid, request.data);
});
