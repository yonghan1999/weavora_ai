import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { existsSync, readFileSync, realpathSync, statSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { isAbsolute, relative, resolve, sep } from "node:path";

export function sha256(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

export function trackedDesignAssets(root) {
  const paths = ["design/prototype", "design/ui"];
  const unstaged = spawnSync("git", ["diff", "--name-only", "-z", "--relative", "--no-ext-diff", "--no-textconv", "--", ...paths], {
    cwd: root, encoding: "utf8", shell: false,
  });
  assert.equal(unstaged.status, 0, unstaged.stderr);
  assert.equal(unstaged.stdout, "", `Tracked design files differ from the index: ${unstaged.stdout.split("\0").filter(Boolean).join(", ")}`);
  const listed = spawnSync("git", ["ls-files", "-z", "--", ...paths], {
    cwd: root,
    encoding: "utf8",
    shell: false,
  });
  assert.equal(listed.status, 0, listed.stderr);
  return listed.stdout.split("\0").filter(Boolean).sort().map(path => {
    const content = spawnSync("git", ["show", `:./${path}`], {
      cwd: root,
      encoding: null,
      shell: false,
      maxBuffer: 32 * 1024 * 1024,
    });
    assert.equal(content.status, 0, content.stderr?.toString("utf8"));
    return {path, sha256: createHash("sha256").update(content.stdout).digest("hex")};
  });
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

function validateMatrixEvidence(item, root) {
  assert.ok(typeof item?.path === "string" && item.path.trim() && !isAbsolute(item.path), "Expected relative evidence path");
  const path = resolve(root, item.path);
  const inside = candidate => candidate !== ".." && !candidate.startsWith(`..${sep}`) && !isAbsolute(candidate);
  assert.ok(inside(relative(root, path)), `Evidence outside workspace: ${item.path}`);
  assert.ok(existsSync(path) && inside(relative(realpathSync(root), realpathSync(path))) && statSync(path).isFile(), `Invalid evidence file: ${item.path}`);
  assert.match(item.sha256, /^[a-f0-9]{64}$/, "Missing evidence SHA-256");
  assert.equal(sha256(path), item.sha256, `Evidence hash mismatch: ${item.path}`);
}

export function validateMatrix(matrix, root) {
  assert.equal(matrix.schemaVersion, 1);
  assert.deepEqual(matrix.platforms.map(item => item.platform).sort(), ["linux", "macos", "windows"]);
  for (const platform of matrix.platforms) {
    assert.ok(platform.system && platform.architecture && platform.permissions);
    assert.ok(Array.isArray(platform.testedGames));
    assert.ok(Array.isArray(platform.evidence));
    const claims = ["shellBuild", "installUpdateUninstall", "realVoiceAndTun"];
    for (const item of platform.evidence) {
      assert.ok(claims.includes(item?.claim), `Invalid evidence claim: ${platform.platform}`);
      validateMatrixEvidence(item, root);
    }
    for (const claim of claims) {
      const status = platform[claim];
      if (platform.platform === "windows" && claim === "shellBuild" && status === "checked_on_windows") continue;
      assert.ok(["unverified", "failed", "verified", "passed"].includes(status), `Invalid status: ${platform.platform}/${claim}`);
      if (status !== "unverified") assert.ok(platform.evidence.some(item => item.claim === claim), `Missing platform evidence: ${platform.platform}/${claim}`);
    }
    for (const game of platform.testedGames) {
      assert.ok(typeof game.name === "string" && game.name.trim() && typeof game.version === "string" && game.version.trim()
        && Array.isArray(game.evidence) && game.evidence.length > 0, `Incomplete game evidence: ${platform.platform}`);
      for (const item of game.evidence) validateMatrixEvidence(item, root);
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
