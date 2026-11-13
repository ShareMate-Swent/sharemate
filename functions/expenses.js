// Co-authored-by: OpenAI Codex <noreply@openai.com>
const { HttpsError } = require("firebase-functions/v2/https");

const FIELDS = ["id", "householdId", "paidBy", "totalAmountCents", "shares"];
const MAX_CENTS = 9_223_372_036_854_775_807n;
const MAX_ID_BYTES = 1500;
const EXPENSES = "expenses";
const OPERATIONS = "expenseSubmissions";

function isId(value) {
  return typeof value === "string" && value.trim().length > 0 &&
    !value.includes("/") && value !== "." && value !== ".." &&
    !/^__.*__$/.test(value) && Buffer.byteLength(value) <= MAX_ID_BYTES;
}

function cents(value, positive = false) {
  if (typeof value !== "string" || !/^(0|[1-9][0-9]{0,18})$/.test(value)) return null;
  const amount = BigInt(value);
  return amount <= MAX_CENTS && (!positive || amount > 0n) ? amount : null;
}

function parseExpense(request) {
  if (!request || typeof request !== "object" || Array.isArray(request) ||
      Object.keys(request).length !== FIELDS.length ||
      !FIELDS.every((key) => Object.hasOwn(request, key)) ||
      ![request.id, request.householdId, request.paidBy].every(isId) ||
      !request.shares || Object.getPrototypeOf(request.shares) !== Object.prototype) return null;
  const total = cents(request.totalAmountCents, true);
  const entries = Object.entries(request.shares);
  if (total === null || entries.length === 0) return null;
  let remaining = total;
  const shares = {};
  for (const [userId, value] of entries) {
    const amount = cents(value);
    if (!isId(userId) || amount === null || amount > remaining) return null;
    remaining -= amount;
    // Define own properties safely even for IDs such as "constructor".
    Object.defineProperty(shares, userId, { value: amount, enumerable: true });
  }
  return remaining === 0n ? { ...request, totalAmountCents: total, shares } : null;
}

function sameExpense(existing, expense) {
  return Object.keys(existing).length === FIELDS.length &&
    FIELDS.filter((key) => key !== "shares").every((key) => existing[key] === expense[key]) &&
    existing.shares && Object.keys(existing.shares).length === Object.keys(expense.shares).length &&
    Object.entries(expense.shares).every(([key, amount]) =>
      Object.hasOwn(existing.shares, key) && existing.shares[key] === amount);
}

async function recordExpense(db, uid, request) {
  if (!uid) throw new HttpsError("unauthenticated", "Sign in to record an expense.");
  const expense = parseExpense(request);
  if (!expense) throw new HttpsError("invalid-argument", "Invalid expense.");
  const household = db.collection("households").doc(expense.householdId);
  const document = household.collection(EXPENSES).doc(expense.id);
  // Bind one submitter's stable operation to one household, including across scope switches.
  const operation = db.collection("users").doc(uid).collection(OPERATIONS).doc(expense.id);
  const revision = household.collection("financeMetadata").doc("settlementRevision");
  const rejection = await db.runTransaction(async (transaction) => {
    try {
      const parent = (await transaction.get(household)).data();
      if (parent?.type !== "SHARED" || !Array.isArray(parent.memberIds) ||
          !parent.memberIds.includes(uid)) {
        throw new HttpsError("permission-denied", "Shared household membership is required.");
      }
      const previous = (await transaction.get(operation)).data();
      const existing = (await transaction.get(document)).data();
      if (previous || existing) {
        if (previous?.householdId !== expense.householdId || !existing ||
            !sameExpense(existing, expense)) {
          throw new HttpsError("already-exists", "Expense ID is already in use.");
        }
        // Historical payer/participants may have left; only the retrying caller must be current.
        return;
      }
      if (![expense.paidBy, ...Object.keys(expense.shares)].every((id) =>
        parent.memberIds.includes(id))) {
        throw new HttpsError("permission-denied", "Payer and participants must be current members.");
      }
      // Share settlement's contention document so concurrent new expenses/payments serialize.
      await transaction.get(revision);
      transaction.create(document, expense);
      transaction.create(operation, { householdId: expense.householdId });
      transaction.set(revision, { lastExpenseId: expense.id });
    } catch (error) {
      // Finish expected no-write rejections before releasing transaction read locks.
      if (error instanceof HttpsError) return error;
      throw error;
    }
  });
  if (rejection) throw rejection;
  return request;
}

module.exports = { recordExpense };
