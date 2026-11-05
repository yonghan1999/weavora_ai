import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { resolve } from "node:path";

export function sha256(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

export function trackedDesignAssets(root) {
  const listed = spawnSync("git", ["ls-files", "-z", "--", "design/prototype", "design/ui"], {
    cwd: root,
    encoding: "utf8",
    shell: false,
  });
  assert.equal(listed.status, 0, listed.stderr);
  return listed.stdout.split("\0").filter(Boolean).sort().map(path => ({
    path,
    sha256: sha256(resolve(root, path)),
  }));
}

export function designAssetDiff(baseline, current) {
  const before = new Map(baseline.assets.map(item => [item.path, item.sha256]));
  const after = new Map(current.map(item => [item.path, item.sha256]));
  const added = [...after.keys()].filter(path => !before.has(path));
  const removed = [...before.keys()].filter(path => !after.has(path));
  const changed = [...after.keys()].filter(path => before.has(path) && before.get(path) !== after.get(path));
  return {
    schemaVersion: 1,
    baseline: "release/design-asset-baseline.json",
    status: added.length + removed.length + changed.length === 0 ? "unchanged" : "changed",
    added,
    removed,
    changed,
    untrackedTestArtifactsExcluded: true,
  };
}

export function validateMatrix(matrix, root) {
  assert.equal(matrix.schemaVersion, 1);
  assert.deepEqual(matrix.platforms.map(item => item.platform).sort(), ["linux", "macos", "windows"]);
  for (const platform of matrix.platforms) {
    assert.ok(platform.system && platform.architecture && platform.permissions);
    assert.ok(Array.isArray(platform.testedGames));
    assert.ok(Array.isArray(platform.evidence));
    const claimed = [platform.shellBuild, platform.installUpdateUninstall, platform.realVoiceAndTun]
      .some(status => ["verified", "passed"].includes(status));
    if (claimed) assert.ok(platform.evidence.length > 0, `Missing platform evidence: ${platform.platform}`);
    for (const path of platform.evidence) assert.ok(existsSync(resolve(root, path)), `Missing evidence: ${path}`);
    for (const game of platform.testedGames) {
      assert.ok(game.name && game.version && game.evidence?.length, `Incomplete game evidence: ${platform.platform}`);
    }
  }
  return matrix;
}

export function validateEvidenceIndex(index, root) {
  assert.equal(index.schemaVersion, 1);
  assert.ok(index.entries.length > 0);
  for (const entry of index.entries) {
    assert.ok(entry.id && entry.category && entry.status && entry.path);
    const path = resolve(root, entry.path);
    assert.ok(existsSync(path), `Missing indexed evidence: ${entry.path}`);
    if (entry.sha256) assert.equal(sha256(path), entry.sha256, `Evidence hash mismatch: ${entry.path}`);
    if (["verified", "passed"].includes(entry.status)) assert.ok(entry.sha256, `Verified evidence requires a hash: ${entry.id}`);
  }
  return index;
}
