// Co-authored-by: Claude Sonnet 5.5 <noreply@anthropic.com>
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { FieldValue } = require("firebase-admin/firestore");
const { deletePrivateHousehold, PAGE_SIZE, MAX_CLEANUP_PASSES } = require("./private-household");

const OWNER = "owner";
const HOME = "owner:home";
const OTHER_HOME = "owner:other";

/** In-memory stand-in for the parts of Firestore and Storage the function uses. */
function fixture() {
  const records = new Map();
  const files = new Set();
  const calls = { deletedPrefixes: [] };
  // Lets a test act "at the same time": [afterInitialRead] runs once, right after the household was
  // first read; [afterCommit] runs after each transaction that wrote; [beforeTransaction] runs
  // before each transaction, so it is the final one when the household has no items or receipts.
  const hooks = { failDeleteFiles: null, failCommit: null, afterCommit: null, beforeTransaction: null,
    afterInitialRead: null };
  // Every document created gets a new version, so a document deleted and created again can be told
  // apart from the original, like Firestore's createTime.
  const versions = new Map();
  let lastVersion = 0;

  // Like a Firestore snapshot, it keeps what the document held when it was read.
  const snapshot = (path) => {
    const version = versions.get(path);
    const data = records.get(path);
    return {
      id: path.split("/").pop(), exists: records.has(path), data: () => data,
      get: (field) => data?.[field],
      createTime: records.has(path) ? { isEqual: (other) => other.version === version, version } : undefined,
    };
  };
  const reference = (path) => ({
    path, id: path.split("/").pop(), get: async () => {
      const read = snapshot(path);
      const act = path.startsWith("households/") ? hooks.afterInitialRead : null;
      hooks.afterInitialRead = act ? null : hooks.afterInitialRead;
      act?.();
      return read;
    },
    collection: (name) => collection(`${path}/${name}`),
    listCollections: async () => [...new Set([...records.keys()]
      .filter((key) => key.startsWith(`${path}/`)).map((key) => key.slice(path.length + 1).split("/")[0]))]
      .map((name) => collection(`${path}/${name}`)),
  });
  // A query is a list of equality filters; [where] can be chained.
  const query = (path, filters) => ({
    where: (field, operator, value) => {
      assert.equal(operator, "==");
      return query(path, [...filters, [field, value]]);
    },
    limit: (count) => ({ get: async () => {
      const docs = [...records.entries()]
        .filter(([key, data]) => key.startsWith(`${path}/`) && !key.slice(path.length + 1).includes("/")
          && filters.every(([field, value]) => data[field] === value))
        .slice(0, count).map(([key]) => ({ ...snapshot(key), ref: reference(key) }));
      return { empty: docs.length === 0, docs };
    } }),
  });
  const collection = (path) => ({
    path, doc: (id) => reference(`${path}/${id}`),
    where: (field, operator, value) => query(path, []).where(field, operator, value),
  });
  const db = {
    collection,
    recursiveDelete: async (target) => {
      [...records.keys()].filter((key) => key.startsWith(`${target.path}/`)).forEach((key) => records.delete(key));
    },
    runTransaction: async (run) => {
      hooks.beforeTransaction?.();
      const writes = [];
      const result = await run({
        get: async (ref) => snapshot(ref.path),
        delete: (ref) => writes.push(() => records.delete(ref.path)),
        set: (ref, data, options) => writes.push(() => records.set(ref.path,
          options?.merge ? { ...records.get(ref.path), ...data } : data)),
      });
      if (writes.length === 0) return result;
      if (hooks.failCommit?.()) throw new Error("commit failed");
      writes.forEach((write) => write());
      hooks.afterCommit?.();
      return result;
    },
  };
  const bucket = {
    deleteFiles: async ({ prefix }) => {
      calls.deletedPrefixes.push(prefix);
      if (hooks.failDeleteFiles?.(prefix)) throw new Error("storage unavailable");
      [...files].filter((file) => file.startsWith(prefix)).forEach((file) => files.delete(file));
    },
  };
  const add = (path, data = {}) => {
    records.set(path, data);
    versions.set(path, ++lastVersion);
  };
  add(`households/${HOME}`, { type: "PRIVATE", createdBy: OWNER, memberIds: [OWNER] });
  add(`users/${OWNER}`, { householdId: HOME, joinCode: "CODE", expirationPreferences: { soonDays: 3 } });
  const remove = (path) => records.delete(path);
  return { db, bucket, records, files, calls, hooks, add, remove };
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

test("an already deleted household is a success, and only the caller's own leftovers are removed", async () => {
  const env = fixture();
  await run(env);
  env.add("items/mine", { householdId: HOME, ownerId: OWNER });
  env.files.add("itemPhotos/mine/1");
  env.add("items/orphan", { householdId: HOME });
  env.add("items/theirs", { householdId: HOME, ownerId: "friend" });
  env.files.add("itemPhotos/orphan/1");

  assert.deepEqual(await run(env), { householdId: HOME });

  assert.equal(env.records.has("items/mine"), false);
  assert.equal(env.files.has("itemPhotos/mine/1"), false);
  assert.equal(env.records.has("items/orphan"), true);
  assert.equal(env.records.has("items/theirs"), true);
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

test("an item added while an earlier pass runs is deleted by the next pass, with its photo", async () => {
  const env = fixture();
  env.add("items/first", { householdId: HOME });
  let once = true;
  env.hooks.afterCommit = () => {
    if (!once) return;
    once = false;
    env.add("items/late", { householdId: HOME });
    env.files.add("itemPhotos/late/1");
  };

  await run(env);

  assert.equal(env.records.has("items/late"), false);
  assert.equal(env.files.has("itemPhotos/late/1"), false);
  assert.equal(env.records.has(`households/${HOME}`), false);
});

test("a household that keeps receiving writes is kept, so the call can be repeated", async () => {
  const env = fixture();
  env.add("items/first", { householdId: HOME });
  let count = 0;
  env.hooks.afterCommit = () => env.add(`items/more${count++}`, { householdId: HOME });

  await assert.rejects(run(env), { code: "aborted" });

  assert.equal(env.records.has(`households/${HOME}`), true);
  assert.equal(env.records.get(`users/${OWNER}`).householdId, HOME);
  assert.equal(count, MAX_CLEANUP_PASSES);

  env.hooks.afterCommit = null;
  await run(env);
  assert.equal(env.records.has(`households/${HOME}`), false);
  assert.equal([...env.records.keys()].some((key) => key.startsWith("items/")), false);
});

test("a household replaced by someone else's before the final step is left alone with the profile link", async () => {
  const env = fixture();
  env.hooks.beforeTransaction = () => {
    env.remove(`households/${HOME}`);
    env.add(`households/${HOME}`, { type: "PRIVATE", createdBy: "intruder", memberIds: ["intruder"] });
  };

  assert.deepEqual(await run(env), { householdId: HOME });

  assert.equal(env.records.get(`households/${HOME}`).createdBy, "intruder");
  assert.equal(env.records.get(`users/${OWNER}`).householdId, HOME);
});

test("a household created after it was found missing is left alone with the profile link", async () => {
  const env = fixture();
  env.remove(`households/${HOME}`);
  env.hooks.beforeTransaction = () =>
    env.add(`households/${HOME}`, { type: "PRIVATE", createdBy: "intruder", memberIds: ["intruder"] });

  await run(env);

  assert.equal(env.records.get(`households/${HOME}`).createdBy, "intruder");
  assert.equal(env.records.get(`users/${OWNER}`).householdId, HOME);
});

test("only the very instance that was authorized is deleted, not one created again with the same data", async () => {
  const env = fixture();
  env.hooks.beforeTransaction = () => {
    env.remove(`households/${HOME}`);
    env.add(`households/${HOME}`, { type: "PRIVATE", createdBy: OWNER, memberIds: [OWNER] });
  };

  await run(env);

  assert.equal(env.records.has(`households/${HOME}`), true);
});

test("a household deleted by another call before the final step is not an error and the profile is unlinked", async () => {
  const env = fixture();
  env.hooks.beforeTransaction = () => env.remove(`households/${HOME}`);

  assert.deepEqual(await run(env), { householdId: HOME });

  assert.equal(env.records.get(`users/${OWNER}`).householdId, FieldValue.delete());
});

test("deletes the owner's receipt backups, deletion markers and receipt photos of the household only", async () => {
  const env = fixture();
  const other = OTHER_HOME;
  env.add(`users/${OWNER}/receiptBackups/mine`, { householdId: HOME });
  env.add(`users/${OWNER}/receiptBackups/other`, { householdId: other });
  env.add(`users/${OWNER}/receiptDeletions/mine`, { householdId: HOME });
  env.add(`users/${OWNER}/receiptDeletions/other`, { householdId: other });
  env.files.add(`receiptPhotos/${HOME}/${OWNER}/mine.jpg`);
  env.files.add(`receiptPhotos/${other}/${OWNER}/other.jpg`);

  await run(env);

  assert.equal(env.records.has(`users/${OWNER}/receiptBackups/mine`), false);
  assert.equal(env.records.has(`users/${OWNER}/receiptDeletions/mine`), false);
  assert.equal(env.files.has(`receiptPhotos/${HOME}/${OWNER}/mine.jpg`), false);
  assert.equal(env.records.has(`users/${OWNER}/receiptBackups/other`), true);
  assert.equal(env.records.has(`users/${OWNER}/receiptDeletions/other`), true);
  assert.equal(env.files.has(`receiptPhotos/${other}/${OWNER}/other.jpg`), true);
  assert.equal(env.records.has(`households/${HOME}`), false);
});

test("a receipt backup saved while an earlier pass runs is deleted by the next pass", async () => {
  const env = fixture();
  env.add(`users/${OWNER}/receiptBackups/first`, { householdId: HOME });
  let once = true;
  env.hooks.afterCommit = () => {
    if (!once) return;
    once = false;
    env.add(`users/${OWNER}/receiptBackups/late`, { householdId: HOME });
  };

  await run(env);

  assert.equal(env.records.has(`users/${OWNER}/receiptBackups/late`), false);
  assert.equal(env.records.has(`households/${HOME}`), false);
});

function createAgain(env, extra = () => {}) {
  env.remove(`households/${HOME}`);
  env.add(`households/${HOME}`, { type: "PRIVATE", createdBy: OWNER, memberIds: [OWNER] });
  env.add("items/new", { householdId: HOME, ownerId: OWNER });
  env.add("receipts/new", { householdId: HOME, ownerId: OWNER });
  env.add(`users/${OWNER}/receiptBackups/new`, { householdId: HOME });
  env.files.add(`receiptPhotos/${HOME}/${OWNER}/new.jpg`);
  env.files.add("itemPhotos/new/photo");
  extra();
}

function assertNewHouseholdUntouched(env) {
  for (const path of ["items/new", "receipts/new", `users/${OWNER}/receiptBackups/new`, `households/${HOME}`]) {
    assert.equal(env.records.has(path), true, path);
  }
  assert.equal(env.files.has(`receiptPhotos/${HOME}/${OWNER}/new.jpg`), true);
  assert.equal(env.files.has("itemPhotos/new/photo"), true);
  assert.equal(env.records.get(`users/${OWNER}`).householdId, HOME);
}

test("a household created again under the same ID before the cleaning starts is not cleaned", async () => {
  const env = fixture();
  env.hooks.afterInitialRead = () => createAgain(env);

  assert.deepEqual(await run(env), { householdId: HOME });

  assertNewHouseholdUntouched(env);
});

test("a household created again under the same ID in the middle of the cleaning is not cleaned", async () => {
  const env = fixture();
  for (let index = 0; index < PAGE_SIZE + 1; index++) env.add(`items/old${index}`, { householdId: HOME });
  let once = true;
  env.hooks.afterCommit = () => {
    if (!once) return;
    once = false;
    createAgain(env);
  };

  assert.deepEqual(await run(env), { householdId: HOME });

  assertNewHouseholdUntouched(env);
});

test("when another call already deleted the household, the cleaning stops and the caller's leftovers are swept", async () => {
  const env = fixture();
  env.hooks.afterInitialRead = () => {
    env.remove(`households/${HOME}`);
    env.add("items/late", { householdId: HOME, ownerId: OWNER });
    env.add("items/theirs", { householdId: HOME, ownerId: "friend" });
  };

  assert.deepEqual(await run(env), { householdId: HOME });

  assert.equal(env.records.has("items/late"), false);
  assert.equal(env.records.has("items/theirs"), true);
  assert.equal(env.records.get(`users/${OWNER}`).householdId, FieldValue.delete());
});

test("what the caller saved after the last cleaning pass is removed once the household is deleted", async () => {
  const env = fixture();
  env.hooks.beforeTransaction = () => {
    env.hooks.beforeTransaction = null;
    env.add("receipts/late", { householdId: HOME, ownerId: OWNER });
    env.add("receipts/friends", { householdId: HOME, ownerId: "friend" });
    env.add("items/late", { householdId: HOME, ownerId: OWNER });
    env.add(`users/${OWNER}/receiptBackups/late`, { householdId: HOME });
    env.files.add(`receiptPhotos/${HOME}/${OWNER}/late.jpg`);
    env.files.add(`receiptPhotos/${HOME}/friend/theirs.jpg`);
  };

  await run(env);

  assert.equal(env.records.has(`households/${HOME}`), false);
  for (const path of ["receipts/late", "items/late", `users/${OWNER}/receiptBackups/late`]) {
    assert.equal(env.records.has(path), false, path);
  }
  assert.equal(env.files.has(`receiptPhotos/${HOME}/${OWNER}/late.jpg`), false);
  assert.equal(env.records.has("receipts/friends"), true);
  assert.equal(env.files.has(`receiptPhotos/${HOME}/friend/theirs.jpg`), true);
});

test("a household replaced before the final transaction is not swept", async () => {
  const env = fixture();
  env.hooks.beforeTransaction = () => {
    env.hooks.beforeTransaction = null;
    createAgain(env);
  };

  await run(env);

  assertNewHouseholdUntouched(env);
});
