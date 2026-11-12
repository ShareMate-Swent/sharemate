// Co-authored-by: OpenAI Codex <noreply@openai.com>
const { HttpsError } = require("firebase-functions/v2/https");

const HOUSEHOLDS = "households";
const FINANCE_METADATA = "financeMetadata";
const SETTLEMENT_REVISION = "settlementRevision";
const SHARED_FRIDGE_TYPE = "SHARED";
const MAX_DOCUMENT_ID_BYTES = 1500;
const INVALID_SETTLEMENT_CODE = "invalid-argument";
const INVALID_SETTLEMENT_MESSAGE = "Invalid settlement.";
const EXPENSES = "expenses";
const SETTLEMENTS = "settlements";
const settlementFields = ["id", "householdId", "payerId", "recipientId", "amountCents"];
const MAX_CENTS = 9_223_372_036_854_775_807n;
const expenseFields = ["id", "householdId", "paidBy", "totalAmountCents", "shares"];

function hasFields(value, fields) {
  return value && typeof value === "object" && !Array.isArray(value) &&
    Object.keys(value).length === fields.length &&
    fields.every((field) => Object.hasOwn(value, field));
}

function isId(value) {
  return typeof value === "string" && value.trim().length > 0 &&
    !value.includes("/") && value !== "." && value !== ".." &&
    !/^__.*__$/.test(value) && Buffer.byteLength(value) <= MAX_DOCUMENT_ID_BYTES;
}

function validSettlement(value) {
  return hasFields(value, settlementFields) &&
    [value.id, value.householdId, value.payerId, value.recipientId].every(isId) &&
    value.payerId !== value.recipientId &&
    typeof value.amountCents === "bigint" && value.amountCents > 0n &&
    value.amountCents <= MAX_CENTS;
}

function requireLedger(condition) {
  if (!condition) throw new HttpsError("failed-precondition", "Invalid household ledger.");
}

function add(nets, userId, delta) {
  nets.set(userId, (nets.get(userId) ?? 0n) + delta);
}

function applyExpense(nets, document, householdId) {
  const expense = document.data();
  requireLedger(hasFields(expense, expenseFields) && expense.id === document.id &&
    expense.householdId === householdId && isId(expense.paidBy) &&
    typeof expense.totalAmountCents === "bigint" && expense.totalAmountCents > 0n &&
    expense.shares && Object.getPrototypeOf(expense.shares) === Object.prototype);
  const total = expense.totalAmountCents;
  let remaining = total;
  for (const [userId, share] of Object.entries(expense.shares)) {
    requireLedger(isId(userId) && typeof share === "bigint" && share >= 0n);
    remaining -= share;
    add(nets, userId, -share);
  }
  requireLedger(remaining === 0n);
  add(nets, expense.paidBy, total);
}

// Match debtor and creditor intervals in the same lexical order as BalanceCalculator.
function debtLimit(nets, payerId, recipientId) {
  let debit = 0n;
  let credit = 0n;
  let payerEnd = 0n;
  let recipientEnd = 0n;
  for (const userId of [...nets.keys()].sort()) {
    const net = nets.get(userId);
    if (net < 0n) debit -= net;
    if (net > 0n) credit += net;
    if (userId === payerId) payerEnd = debit;
    if (userId === recipientId) recipientEnd = credit;
  }
  const payerNet = nets.get(payerId) ?? 0n;
  const recipientNet = nets.get(recipientId) ?? 0n;
  if (payerNet >= 0n || recipientNet <= 0n) return 0n;
  const payerStart = payerEnd + payerNet;
  const recipientStart = recipientEnd - recipientNet;
  const start = payerStart > recipientStart ? payerStart : recipientStart;
  const end = payerEnd < recipientEnd ? payerEnd : recipientEnd;
  return end > start ? end - start : 0n;
}

async function recordSettlement(db, uid, request) {
  if (!uid) throw new HttpsError("unauthenticated", "Sign in to record a settlement.");
  // Decimal strings preserve all signed 64-bit cents across the callable JSON boundary.
  if (!hasFields(request, settlementFields) || typeof request.amountCents !== "string" ||
      !/^[1-9][0-9]{0,18}$/.test(request.amountCents)) {
    throw new HttpsError(INVALID_SETTLEMENT_CODE, INVALID_SETTLEMENT_MESSAGE);
  }
  const payment = { ...request, amountCents: BigInt(request.amountCents) };
  if (!validSettlement(payment)) {
    throw new HttpsError(INVALID_SETTLEMENT_CODE, INVALID_SETTLEMENT_MESSAGE);
  }
  if (uid !== payment.payerId) {
    throw new HttpsError("permission-denied", "Only the payer can record this settlement.");
  }

  const household = db.collection(HOUSEHOLDS).doc(payment.householdId);
  const settlements = household.collection(SETTLEMENTS);
  const operation = settlements.doc(payment.id);
  const revision = household.collection(FINANCE_METADATA).doc(SETTLEMENT_REVISION);
  const rejection = await db.runTransaction(async (transaction) => {
    try {
      const parent = (await transaction.get(household)).data();
      if (parent?.type !== SHARED_FRIDGE_TYPE || !Array.isArray(parent.memberIds) ||
          !parent.memberIds.includes(uid)) {
        throw new HttpsError("permission-denied", "Shared household membership is required.");
      }

      const existing = (await transaction.get(operation)).data();
      if (existing) {
        if (!settlementFields.every((field) => existing[field] === payment[field])) {
          throw new HttpsError("already-exists", "Settlement ID is already in use.");
        }
        return;
      }
      // Force new settlements to conflict even when they use different operation IDs.
      await transaction.get(revision);
      const expenses = await transaction.get(household.collection(EXPENSES));
      const history = await transaction.get(settlements);
      const nets = new Map();
      for (const document of expenses.docs) applyExpense(nets, document, payment.householdId);
      for (const document of history.docs) {
        const previous = document.data();
        requireLedger(validSettlement(previous) && previous.id === document.id &&
          previous.householdId === payment.householdId);
        const amount = previous.amountCents;
        add(nets, previous.payerId, amount);
        add(nets, previous.recipientId, -amount);
      }
      if (payment.amountCents > debtLimit(nets, uid, payment.recipientId)) {
        throw new HttpsError("failed-precondition", "The settlement exceeds the outstanding debt.");
      }

      transaction.create(operation, payment);
      transaction.set(revision, { lastSettlementId: payment.id });
    } catch (error) {
      // Complete an attempt with no writes before reporting an expected validation rejection.
      // The Firestore SDK can otherwise release its read locks asynchronously after rollback.
      if (error instanceof HttpsError) return error;
      throw error;
    }
  });
  if (rejection) throw rejection;
  return request;
}

module.exports = { recordSettlement };
