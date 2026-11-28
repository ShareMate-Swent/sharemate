// Co-authored-by: OpenAI Codex <noreply@openai.com>
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { suggestRecipes } = require("./recipes");
const secret = "test-secret-not-a-real-key";
const data = { householdId: "home", ingredients: ["milk", "apple"] };
const row = { id: 12, title: "Soup", image: "https://example.org/soup.jpg", usedIngredientCount: 2, missedIngredientCount: 1, missedIngredients: [{ name: "salt" }] };
const db = (members = ["alice"]) => {
  const budgets = new Map();
  return {
    budgets,
    collection(name) {
      assert.ok(["households", "recipeRequestBudgets"].includes(name));
      return { doc(id) {
        if (name === "households") {
          assert.ok(["home", "other-home"].includes(id));
          return { get: async () => ({ data: () => members === null ? undefined : { memberIds: members } }) };
        }
        return { id };
      } };
    },
    async runTransaction(run) {
      const writes = [];
      const result = await run({
        get: async ref => ({ exists: budgets.has(ref.id), data: () => budgets.get(ref.id) }),
        set: (ref, value) => writes.push([ref.id, value]),
      });
      writes.forEach(([id, value]) => budgets.set(id, value));
      return result;
    },
  };
};
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
    assert.equal(url.searchParams.get("ranking"), "2");
    assert.equal(options.headers["x-api-key"], secret);
    assert.equal(options.redirect, "error");
    assert.equal(url.toString().includes(secret), false);
    return response([{ ...row, providerSecret: secret }]);
  });
  assert.deepEqual(result, { recipes: [{ id: "12", title: "Soup", imageUrl: row.image,
    matchingIngredientCount: 2, missingIngredientCount: 1, missingIngredients: ["salt"] }] });
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


test("invalid, unauthorized and unconfigured calls never reserve budget", async () => {
  const never = () => assert.fail("No HTTP expected");
  for (const [uid, payload, key, members, code] of [
    [null, data, () => secret, ["alice"], "unauthenticated"],
    ["alice", { ...data, ingredients: [] }, () => secret, ["alice"], "invalid-argument"],
    ["alice", data, () => secret, ["bob"], "permission-denied"],
    ["alice", data, () => "", ["alice"], "failed-precondition"],
  ]) {
    const store = db(members);
    let reservations = 0;
    store.runTransaction = () => { reservations++; throw Error("Must not reserve"); };
    await assert.rejects(suggestRecipes(store, uid, payload, key, never), { code });
    assert.equal(reservations, 0);
    assert.equal(store.budgets.size, 0);
  }
});

test("reservation failure prevents HTTP and hides storage diagnostics", async () => {
  const store = db();
  store.runTransaction = async () => { throw Error(secret); };
  let calls = 0;
  await assert.rejects(suggestRecipes(store, "alice", data, () => secret, () => { calls++; return response([]); }),
    error => error.code === "internal" && !JSON.stringify(error).includes(secret));
  assert.equal(calls, 0);
});

test("household and ingredient changes cannot bypass a UID reservation", async () => {
  const store = db();
  let calls = 0;
  const http = async () => { calls++; return response([]); };
  await suggestRecipes(store, "alice", data, () => secret, http);
  await assert.rejects(suggestRecipes(store, "alice", { householdId: "other-home", ingredients: ["rice"] },
    () => secret, http), { code: "resource-exhausted" });
  assert.equal(calls, 1);
  assert.equal(store.budgets.size, 1);
});

test("provider failures and timeouts never refund a committed reservation", async () => {
  for (const timedOut of [false, true]) {
    const store = db();
    let calls = 0;
    const http = async () => { calls++; return timedOut ? new Promise(() => {}) : response({}, 500); };
    await assert.rejects(suggestRecipes(store, "alice", data, () => secret, http, 10),
      { code: timedOut ? "deadline-exceeded" : "internal" });
    assert.equal([...store.budgets.values()][0].count, 1);
    await assert.rejects(suggestRecipes(store, "alice", data, () => secret, http, 10), { code: "resource-exhausted" });
    assert.equal(calls, 1);
  }
});

test("missing ingredient names are forwarded in provider order for zero, one and many", async () => {
  for (const names of [[], ["salt"], ["salt", "onion", "pepper"]]) {
    const result = await call(async () => response([{ ...row, missedIngredientCount: names.length,
      missedIngredients: names.map(name => ({ name, unusedProviderField: secret })) }]));
    assert.deepEqual(result.recipes[0].missingIngredients, names);
    assert.equal(JSON.stringify(result).includes(secret), false);
  }
});

test("missing ingredient lists must match the provider count and contain valid names", async () => {
  for (const missing of [null, "salt", [], [{ name: "" }], [{ name: 12 }], [{}], [{ name: "x".repeat(201) }]]) {
    await assert.rejects(call(async () => response([{ ...row, missedIngredients: missing }])), { code: "internal" });
  }
});


test("ingredient name boundary accepts 200, trims padding and rejects malformed present names", async () => {
  for (const [raw, expected] of [["x".repeat(200), "x".repeat(200)], [" salt ", "salt"]]) {
    const result = await call(async () => response([{ ...row, missedIngredientCount: 1, missedIngredients: [{ name: raw }] }]));
    assert.deepEqual(result.recipes[0].missingIngredients, [expected]);
  }
  for (const entry of [{ name: "x".repeat(201) }, { name: "   " }, null]) {
    await assert.rejects(call(async () => response([{ ...row, missedIngredientCount: 1, missedIngredients: [entry] }])), { code: "internal" });
  }
});

test("one inconsistent missing list rejects the batch rather than inventing partial metadata", async () => {
  await assert.rejects(call(async () => response([row, { ...row, id: 13, missedIngredients: [null] }])), { code: "internal" });
});
