// Co-authored-by: OpenAI Codex <noreply@openai.com>
// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
const HOUSEHOLDS = "households";
const FINANCE_METADATA = "financeMetadata";
const SETTLEMENT_REVISION = "settlementRevision";
const SHARED_FRIDGE_TYPE = "SHARED";
const PRIVATE_FRIDGE_TYPE = "PRIVATE";
const MAX_DOCUMENT_ID_BYTES = 1500;
const MAX_CENTS = 9_223_372_036_854_775_807n;

function isId(value) {
  return typeof value === "string" && value.trim().length > 0 &&
    !value.includes("/") && value !== "." && value !== ".." &&
    !/^__.*__$/.test(value) && Buffer.byteLength(value) <= MAX_DOCUMENT_ID_BYTES;
}

module.exports = { HOUSEHOLDS, FINANCE_METADATA, SETTLEMENT_REVISION, SHARED_FRIDGE_TYPE, PRIVATE_FRIDGE_TYPE, MAX_CENTS, isId };
