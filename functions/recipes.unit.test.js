// Co-authored-by: OpenAI Codex <noreply@openai.com>
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { suggestRecipes } = require("./recipes");
const secret = "test-secret-not-a-real-key";
const data = { householdId: "home", ingredients: ["milk", "apple"] };
const row = { id: 12, title: "Soup", image: "https://example.org/soup.jpg", usedIngredientCount: 2, missedIngredientCount: 1 };
const db = (members = ["alice"]) => ({ collection: name => {
  assert.equal(name, "households");
  return { doc: id => { assert.equal(id, "home"); return { get: async () => ({ data: () => members === null ? undefined : { memberIds: members } }) }; } };
} });
const response = (rows = [row], status = 200) => ({ ok: status === 200, status, text: async () => JSON.stringify(rows) });
const call = transport => suggestRecipes(db(), "alice", data, () => secret, transport, 100);

test("auth and membership failures never call the provider", async () => {
  const never = () => assert.fail("Provider must not be called");
  await assert.rejects(suggestRecipes(db(), null, data, never, never), { code: "unauthenticated" });
  for (const members of [[], ["bob"], null])
    await assert.rejects(suggestRecipes(db(members), "alice", data, never, never), { code: "permission-denied" });
});

test("bounds and validates the complete request", async () => {
  const invalid = [null, [], {}, { ...data, extra: true }, { ...data, householdId: "../home" },
    { ...data, ingredients: [] }, { ...data, ingredients: Array(51).fill("milk") },
    ...[null, 1, " ", "a".repeat(81), "milk\n"].map(name => ({ ...data, ingredients: [name] }))];
  for (const payload of invalid)
    await assert.rejects(suggestRecipes(db(), "alice", payload, () => secret, () => assert.fail()), { code: "invalid-argument" });
});

test("maps only the application contract and keeps the key out of URLs and responses", async () => {
  const result = await call(async (url, options) => {
    assert.equal(url.origin, "https://api.spoonacular.com");
    assert.equal(url.pathname, "/recipes/findByIngredients");
    assert.equal(url.searchParams.get("ingredients"), "milk,apple");
    assert.equal(url.searchParams.get("number"), "10");
    assert.equal(options.headers["x-api-key"], secret);
    assert.equal(options.redirect, "error");
    assert.equal(url.toString().includes(secret), false);
    return response([{ ...row, providerSecret: secret }]);
  });
  assert.deepEqual(result, { recipes: [{ id: "12", title: "Soup", imageUrl: row.image,
    matchingIngredientCount: 2, missingIngredientCount: 1 }] });
  assert.equal(JSON.stringify(result).includes(secret), false);
  assert.deepEqual(await call(async () => response([])), { recipes: [] });
  assert.equal((await call(async () => response([{ ...row, image: undefined }]))).recipes[0].imageUrl, null);
});

test("provider errors, quota and invalid payloads expose only safe errors", async () => {
  for (const status of [401, 402, 429, 500]) {
    const expected = [402, 429].includes(status) ? "resource-exhausted" : "internal";
    await assert.rejects(call(async () => response({ secret }, status)), error =>
      error.code === expected && !JSON.stringify(error).includes(secret));
  }
  const invalid = [{}, [null], [{ ...row, id: 1.5 }], [{ ...row, title: "" }],
    [{ ...row, missedIngredientCount: -1 }], [{ ...row, image: "file:///secret" }], [row, row], Array(11).fill(row)];
  for (const rows of invalid) await assert.rejects(call(async () => response(rows)), { code: "internal" });
  for (const transport of [async () => { throw Error(secret); },
    async () => ({ ok: true, text: async () => secret }),
    async () => ({ ok: true, text: async () => "x".repeat(1000001) })])
    await assert.rejects(call(transport), error => error.code === "internal" && !JSON.stringify(error).includes(secret));
  await assert.rejects(suggestRecipes(db(), "alice", data, () => "", () => assert.fail()), { code: "failed-precondition" });
  await assert.rejects(suggestRecipes({ collection: () => { throw Error(secret); } }, "alice", data, () => secret),
    error => error.code === "internal" && !JSON.stringify(error).includes(secret));
});

test("timeout bounds both the HTTP request and reading its response body", async () => {
  for (const hangInBody of [false, true]) {
    let signal;
    const transport = async (_, options) => {
      signal = options.signal;
      if (hangInBody) return { ok: true, text: () => new Promise(() => {}) };
      return new Promise(() => {});
    };
    await assert.rejects(suggestRecipes(db(), "alice", data, () => secret, transport, 10), { code: "deadline-exceeded" });
    assert.equal(signal.aborted, true);
  }
});
