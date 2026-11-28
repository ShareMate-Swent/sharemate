// Co-authored-by: OpenAI Codex <noreply@openai.com>
const { HttpsError } = require("firebase-functions/v2/https");
const { isId } = require("./finance-common");
const { reserveRecipeRequest } = require("./recipe-rate-limit");
const RESULT_LIMIT = 10;
// Shared ceiling for recipe titles and missing-ingredient names in the application contract.
const MAX_RECIPE_TEXT_LENGTH = 200;
const MAX_INGREDIENTS = 50;
const TIMEOUT_MILLIS = 8000;

function decodeRecipes(rows) {
  if (!Array.isArray(rows) || rows.length > RESULT_LIMIT) throw new Error("Invalid recipes");
  const recipes = rows.map(row => {
    if (!row || !Number.isSafeInteger(row.id) || row.id <= 0 ||
        typeof row.title !== "string" || !row.title.trim() || row.title.length > MAX_RECIPE_TEXT_LENGTH ||
        ![row.usedIngredientCount, row.missedIngredientCount].every(n =>
          Number.isInteger(n) && n >= 0 && n <= 2147483647)) throw new Error("Invalid recipe");
    const imageUrl = row.image ?? null;
    if (imageUrl !== null && (typeof imageUrl !== "string" || imageUrl.length > 2048 ||
        new URL(imageUrl).protocol !== "https:")) throw new Error("Invalid image");
    const missing = row.missedIngredients ?? [];
    if (!Array.isArray(missing) || missing.length !== row.missedIngredientCount ||
        !missing.every(item => item && typeof item.name === "string" &&
          item.name.trim().length > 0 && item.name.length <= MAX_RECIPE_TEXT_LENGTH)) throw new Error("Invalid missing ingredients");
    return { id: String(row.id), title: row.title, imageUrl,
      matchingIngredientCount: row.usedIngredientCount, missingIngredientCount: row.missedIngredientCount,
      missingIngredients: missing.map(item => item.name.trim()) };
  });
  if (new Set(recipes.map(row => row.id)).size !== recipes.length) throw new Error("Duplicate recipes");
  return { recipes };
}

/** HTTP transport is injectable; production always uses the fixed HTTPS provider endpoint. */
async function suggestRecipes(db, uid, data, key, transport = fetch, timeoutMillis = TIMEOUT_MILLIS) {
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first.");
  if (!data || Object.keys(data).sort().join() !== "householdId,ingredients" ||
      !isId(data.householdId) || !Array.isArray(data.ingredients) ||
      data.ingredients.length < 1 || data.ingredients.length > MAX_INGREDIENTS ||
      !data.ingredients.every(name => typeof name === "string" && name.trim().length > 0 &&
        name.length <= 80 && !/[\u0000-\u001f\u007f]/.test(name))) {
    throw new HttpsError("invalid-argument", "Invalid recipe request.");
  }
  let home;
  try {
    home = (await db.collection("households").doc(data.householdId).get()).data();
  } catch (_) {
    throw new HttpsError("internal", "Unable to verify household access.");
  }
  if (!Array.isArray(home?.memberIds) || !home.memberIds.includes(uid))
    throw new HttpsError("permission-denied", "Household membership required.");
  const controller = new AbortController();
  let timer;
  try {
    const apiKey = key();
    if (typeof apiKey !== "string" || !apiKey.trim())
      throw new HttpsError("failed-precondition", "Recipe service is not configured.");
    await reserveRecipeRequest(db, uid);
    const url = new URL("https://api.spoonacular.com/recipes/findByIngredients");
    url.searchParams.set("ingredients", data.ingredients.join(","));
    url.searchParams.set("number", String(RESULT_LIMIT));
    // Rank the bounded provider result pool by missing ingredients before Android's stable sort.
    url.searchParams.set("ranking", "2");
    return await Promise.race([
      (async () => {
        const response = await transport(url, { headers: { "x-api-key": apiKey }, signal: controller.signal, redirect: "error" });
        if (response.status === 402 || response.status === 429)
          throw new HttpsError("resource-exhausted", "Recipe service quota is temporarily unavailable.");
        if (!response.ok) throw new HttpsError("internal", "Recipe service is unavailable.");
        const text = await response.text();
        if (text.length > 1000000) throw new Error("Oversized response");
        return decodeRecipes(JSON.parse(text));
      })(),
      new Promise((_, reject) => {
        timer = setTimeout(() => {
          controller.abort();
          reject(new HttpsError("deadline-exceeded", "Recipe service timed out."));
        }, timeoutMillis);
      }),
    ]);
  } catch (error) {
    if (error instanceof HttpsError) throw error;
    throw new HttpsError("internal", "Recipe service is unavailable.");
  } finally {
    clearTimeout(timer);
  }
}
module.exports = { suggestRecipes };
