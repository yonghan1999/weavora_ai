import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { sha256, validateMatrix } from "./compatibility-tool.mjs";

test("support claims require matching file contents and capability; games carry their own evidence", context => {
  const root = mkdtempSync(join(tmpdir(), "support-matrix-"));
  context.after(() => rmSync(root, {recursive: true, force: true}));
  const path = "observations.txt";
  writeFileSync(join(root, path), "synthetic validator fixture only");
  const digest = sha256(join(root, path));
  const matrix = JSON.parse(readFileSync(new URL("../release/support-matrix.json", import.meta.url)));
  for (const row of matrix.platforms) Object.assign(row, {
    shellBuild: "unverified", installUpdateUninstall: "unverified", realVoiceAndTun: "unverified", evidence: [], testedGames: [],
  });
  const platform = matrix.platforms.find(row => row.platform === "windows");
  const validate = () => validateMatrix(matrix, root);
  validate();
  platform.installUpdateUninstall = "verified";
  assert.throws(validate, /Missing platform evidence/);
  platform.evidence = [{claim: "shellBuild", path, sha256: digest}];
  assert.throws(validate, /Missing platform evidence/);
  platform.evidence[0].claim = "installUpdateUninstall";
  validate();
  platform.realVoiceAndTun = "passed";
  assert.throws(validate, /Missing platform evidence/);
  platform.realVoiceAndTun = "unverified";
  platform.installUpdateUninstall = "verifed";
  assert.throws(validate, /Invalid status/);
  platform.installUpdateUninstall = "verified";
  platform.testedGames = [{name: "Fixture game", version: "1.0", evidence: [{path, sha256: digest}]}];
  validate();
  for (const evidence of [[], "missing.txt", [{path: "missing.txt", sha256: digest}], [{path, sha256: "0".repeat(64)}],
    [{path: join(root, path), sha256: digest}], [{path: "../outside.txt", sha256: digest}], [{path: ".", sha256: digest}]]) {
    platform.testedGames[0].evidence = evidence;
    assert.throws(validate);
  }
  platform.testedGames = [];
  platform.installUpdateUninstall = "failed";
  validate();
  writeFileSync(join(root, path), "changed observations");
  assert.throws(validate, /hash mismatch/);
});
