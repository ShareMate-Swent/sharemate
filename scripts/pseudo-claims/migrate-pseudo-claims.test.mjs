// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
import assert from "node:assert/strict";
import test from "node:test";
import { groupLegacyPseudos, normalizePseudo, pseudoClaimId } from "./migrate-pseudo-claims.mjs";

test("normalizes case, outer invisible padding, and compatibility characters", () => {
  assert.equal(normalizePseudo("  Alex\u200B "), "alex");
  assert.equal(pseudoClaimId("Alex"), pseudoClaimId("  ALEX  "));
  assert.equal(normalizePseudo("\uFF33hareMate"), "sharemate");
});

test("groups every existing owner without changing existing names", () => {
  const claims = groupLegacyPseudos([
    { uid: "first", displayName: "Alex" },
    { uid: "second", displayName: " alex " },
    { uid: "first", displayName: "Profile Name" },
    { uid: "second", displayName: " profile name " },
    { uid: "third", displayName: "Other" },
    { uid: "missing-name", displayName: "" },
    { displayName: "No uid" },
  ]);

  assert.deepEqual([...claims.get(pseudoClaimId("alex"))].sort(), ["first", "second"]);
  assert.deepEqual([...claims.get(pseudoClaimId("other"))], ["third"]);
  assert.deepEqual([...claims.get(pseudoClaimId("profile name"))].sort(), ["first", "second"]);
  assert.equal(claims.size, 3);
});
