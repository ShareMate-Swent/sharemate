// Co-authored-by: OpenAI Codex <noreply@openai.com>
const { createHash } = require("node:crypto");
const { HttpsError } = require("firebase-functions/v2/https");
const DAILY_LIMIT = 10;
const MIN_INTERVAL_MILLIS = 30000;

/** Admin-only root collection: existing Firestore rules grant clients no access. */
function recipeBudgetDocument(db, uid) {
  const id = createHash("sha256").update(uid).digest("hex");
  return db.collection("recipeRequestBudgets").doc(id);
}

/** Reserve before HTTP, never refund. The server clock is re-read on transaction retries. */
async function reserveRecipeRequest(db, uid, now = Date.now) {
  try {
    const ref = recipeBudgetDocument(db, uid);
    await db.runTransaction(async tx => {
      const snapshot = await tx.get(ref);
      const previous = snapshot.exists ? snapshot.data() : null;
      const current = now();
      if (!Number.isSafeInteger(current) || current < 0) throw new Error("Invalid clock");
      const day = new Date(current).toISOString().slice(0, 10);
      if (previous && (!Number.isInteger(previous.count) || previous.count < 1 || previous.count > DAILY_LIMIT ||
          !Number.isSafeInteger(previous.lastAcceptedAt) || previous.lastAcceptedAt < 0 ||
          previous.day !== new Date(previous.lastAcceptedAt).toISOString().slice(0, 10))) {
        throw new Error("Invalid budget");
      }
      const count = previous?.day === day ? previous.count : 0;
      // Also applies across midnight; clock rollback cannot reopen an earlier day's allowance.
      if (count >= DAILY_LIMIT || (previous && current - previous.lastAcceptedAt < MIN_INTERVAL_MILLIS)) {
        throw new HttpsError("resource-exhausted", "Recipe request limit reached. Please try again later.");
      }
      tx.set(ref, { day, count: count + 1, lastAcceptedAt: current });
    });
  } catch (error) {
    if (error instanceof HttpsError && error.code === "resource-exhausted") throw error;
    throw new HttpsError("internal", "Unable to reserve a recipe request.");
  }
}
module.exports = { reserveRecipeRequest, recipeBudgetDocument };
