// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { FieldValue } = require("firebase-admin/firestore");
const { deletePrivateHousehold, PAGE_SIZE } = require("./private-household");

const OWNER = "owner";
const HOME = "owner:home";
const OTHER_HOME = "owner:other";

/** In-memory stand-in for the parts of Firestore and Storage the function uses. */
function fixture() {
  const records = new Map();
  const files = new Set();
  const calls = { deletedPrefixes: [] };
  const hooks = { failDeleteFiles: null, failCommit: null };

  const snapshot = (path) => ({
    id: path.split("/").pop(), exists: records.has(path), data: () => records.get(path),
    get: (field) => records.get(path)?.[field],
  });
  const reference = (path) => ({
    path, id: path.split("/").pop(), get: async () => snapshot(path),
    collection: (name) => collection(`${path}/${name}`),
    listCollections: async () => [...new Set([...records.keys()]
      .filter((key) => key.startsWith(`${path}/`)).map((key) => key.slice(path.length + 1).split("/")[0]))]
      .map((name) => collection(`${path}/${name}`)),
  });
  const collection = (path) => ({
    path, doc: (id) => reference(`${path}/${id}`),
    where: (field, operator, value) => ({ limit: (count) => ({ get: async () => {
      assert.equal(operator, "==");
      const docs = [...records.entries()]
        .filter(([key, data]) => key.startsWith(`${path}/`) && !key.slice(path.length + 1).includes("/") && data[field] === value)
        .slice(0, count).map(([key]) => ({ ...snapshot(key), ref: reference(key) }));
      return { empty: docs.length === 0, docs };
    } }) }),
  });
  const db = {
    collection,
    batch: () => {
      const paths = [];
      return {
        delete: (ref) => paths.push(ref.path),
        commit: async () => {
          if (hooks.failCommit?.()) throw new Error("commit failed");
          paths.forEach((path) => records.delete(path));
        },
      };
    },
    recursiveDelete: async (target) => {
      [...records.keys()].filter((key) => key.startsWith(`${target.path}/`)).forEach((key) => records.delete(key));
    },
    runTransaction: async (run) => {
      const writes = [];
      await run({
        get: async (ref) => snapshot(ref.path),
        delete: (ref) => writes.push(() => records.delete(ref.path)),
        set: (ref, data, options) => writes.push(() => records.set(ref.path,
          options?.merge ? { ...records.get(ref.path), ...data } : data)),
      });
      writes.forEach((write) => write());
    },
  };
  const bucket = {
    deleteFiles: async ({ prefix }) => {
      calls.deletedPrefixes.push(prefix);
      if (hooks.failDeleteFiles?.(prefix)) throw new Error("storage unavailable");
      [...files].filter((file) => file.startsWith(prefix)).forEach((file) => files.delete(file));
    },
  };
  const add = (path, data = {}) => records.set(path, data);
  add(`households/${HOME}`, { type: "PRIVATE", createdBy: OWNER, memberIds: [OWNER] });
  add(`users/${OWNER}`, { householdId: HOME, joinCode: "CODE", expirationPreferences: { soonDays: 3 } });
  return { db, bucket, records, files, calls, hooks, add };
}

function run(env, uid = OWNER, request = { householdId: HOME }) {
  return deletePrivateHousehold(env.db, env.bucket, uid, request);
}

test("deletes the household, its items with photos, receipts, sub-collections and incident photos", async () => {
  const env = fixture();
  env.add("items/a", { householdId: HOME });
  env.add("items/b", { householdId: HOME });
  env.add("receipts/r", { householdId: HOME });
  env.add(`households/${HOME}/memberNames/${OWNER}`, { displayName: "Owner" });
  [`itemPhotos/a/1`, `itemPhotos/b/1`, `incidentPhotos/${HOME}/${OWNER}/1`].forEach((file) => env.files.add(file));

  assert.deepEqual(await run(env), { householdId: HOME });

  assert.equal(env.records.has(`households/${HOME}`), false);
  assert.equal(env.records.has(`households/${HOME}/memberNames/${OWNER}`), false);
  assert.equal([...env.records.keys()].some((key) => key.startsWith("items/") || key.startsWith("receipts/")), false);
  assert.equal(env.files.size, 0);
});

test("unlinks the profile and keeps its other fields", async () => {
  const env = fixture();
  await run(env);
  const user = env.records.get(`users/${OWNER}`);
  assert.equal(user.householdId, FieldValue.delete());
  assert.equal(user.joinCode, FieldValue.delete());
  assert.deepEqual(user.expirationPreferences, { soonDays: 3 });
});

test("leaves other households, other items, personal receipts and the profile link alone", async () => {
  const env = fixture();
  env.add(`households/${OTHER_HOME}`, { type: "SHARED", createdBy: OWNER, memberIds: [OWNER, "friend"] });
  env.add("items/mine", { householdId: HOME });
  env.add("items/shared", { householdId: OTHER_HOME });
  env.add("receipts/personal", { ownerId: OWNER });
  env.add("receipts/linked", { householdId: OTHER_HOME });
  env.files.add("itemPhotos/shared/1");
  env.add(`users/${OWNER}`, { householdId: OTHER_HOME, joinCode: "OTHER" });

  await run(env);

  assert.deepEqual([...env.records.keys()].sort(), [
    `households/${OTHER_HOME}`, "items/shared", "receipts/linked", "receipts/personal", `users/${OWNER}`]);
  assert.deepEqual(env.records.get(`users/${OWNER}`), { householdId: OTHER_HOME, joinCode: "OTHER" });
  assert.equal(env.files.has("itemPhotos/shared/1"), true);
});

test("deletes more items than fit in one page", async () => {
  const env = fixture();
  const count = PAGE_SIZE + 5;
  for (let index = 0; index < count; index++) env.add(`items/item${index}`, { householdId: HOME });

  await run(env);

  assert.equal([...env.records.keys()].filter((key) => key.startsWith("items/")).length, 0);
  assert.equal(env.calls.deletedPrefixes.filter((prefix) => prefix.startsWith("itemPhotos/")).length, count);
});

test("a failed photo deletion keeps the household and the item so the call can be repeated", async () => {
  const env = fixture();
  env.add("items/a", { householdId: HOME });
  env.hooks.failDeleteFiles = () => true;

  await assert.rejects(run(env), { message: "storage unavailable" });

  assert.equal(env.records.has(`households/${HOME}`), true);
  assert.equal(env.records.has("items/a"), true);
  assert.equal(env.records.get(`users/${OWNER}`).householdId, HOME);

  env.hooks.failDeleteFiles = null;
  await run(env);
  assert.equal(env.records.has(`households/${HOME}`), false);
  assert.equal(env.records.has("items/a"), false);
});

test("a retry only redoes the work that is left", async () => {
  const env = fixture();
  const count = PAGE_SIZE + 1;
  for (let index = 0; index < count; index++) env.add(`items/item${index}`, { householdId: HOME });
  let commits = 0;
  env.hooks.failCommit = () => ++commits === 2;

  await assert.rejects(run(env), { message: "commit failed" });
  const remaining = [...env.records.keys()].filter((key) => key.startsWith("items/")).length;
  assert.equal(remaining, 1);
  assert.equal(env.records.has(`households/${HOME}`), true);

  env.calls.deletedPrefixes.length = 0;
  await run(env);
  assert.equal(env.calls.deletedPrefixes.filter((prefix) => prefix.startsWith("itemPhotos/")).length, remaining);
  assert.equal(env.records.has(`households/${HOME}`), false);
});

test("an already deleted household is a success and touches nothing else", async () => {
  const env = fixture();
  await run(env);
  env.add("items/orphan", { householdId: HOME });
  env.files.add("itemPhotos/orphan/1");

  assert.deepEqual(await run(env), { householdId: HOME });

  assert.equal(env.records.has("items/orphan"), true);
  assert.equal(env.files.has("itemPhotos/orphan/1"), true);
});

test("a missing household still unlinks a profile that points at it", async () => {
  const env = fixture();
  env.records.delete(`households/${HOME}`);
  await run(env);
  assert.equal(env.records.get(`users/${OWNER}`).householdId, FieldValue.delete());
});

test("a missing profile is not an error", async () => {
  const env = fixture();
  env.records.delete(`users/${OWNER}`);
  await run(env);
  assert.equal(env.records.has(`households/${HOME}`), false);
  assert.equal(env.records.has(`users/${OWNER}`), false);
});

test("refuses anyone but the creator, and anything but a private household with one member", async () => {
  const cases = [
    ["another user", (env) => run(env, "intruder"), "permission-denied"],
    ["a shared household", (env) => {
      env.add(`households/${HOME}`, { type: "SHARED", createdBy: OWNER, memberIds: [OWNER] });
      return run(env);
    }, "failed-precondition"],
    ["a private household with an extra member", (env) => {
      env.add(`households/${HOME}`, { type: "PRIVATE", createdBy: OWNER, memberIds: [OWNER, "friend"] });
      return run(env);
    }, "failed-precondition"],
    ["a household without members", (env) => {
      env.add(`households/${HOME}`, { type: "PRIVATE", createdBy: OWNER });
      return run(env);
    }, "failed-precondition"],
  ];
  for (const [name, attempt, code] of cases) {
    const env = fixture();
    env.add("items/a", { householdId: HOME });
    env.files.add("itemPhotos/a/1");
    await assert.rejects(attempt(env), { code }, name);
    assert.equal(env.records.has("items/a"), true, name);
    assert.equal(env.files.has("itemPhotos/a/1"), true, name);
    assert.equal(env.records.has(`households/${HOME}`), true, name);
  }
});

test("requires a signed-in caller and a well-formed payload before touching anything", async () => {
  const env = fixture();
  for (const uid of [undefined, null, ""]) {
    await assert.rejects(deletePrivateHousehold(env.db, env.bucket, uid, { householdId: HOME }),
      { code: "unauthenticated" });
  }
  const malformed = [null, undefined, [], "text", {}, { householdId: "" }, { householdId: " " },
    { householdId: 7 }, { householdId: "a/b" }, { householdId: "." }, { householdId: "__x__" },
    { householdId: HOME, extra: "forged" }];
  for (const request of malformed) {
    await assert.rejects(deletePrivateHousehold(env.db, env.bucket, OWNER, request),
      { code: "invalid-argument" }, JSON.stringify(request));
  }
  assert.equal(env.records.has(`households/${HOME}`), true);
  assert.deepEqual(env.calls.deletedPrefixes, []);
});
