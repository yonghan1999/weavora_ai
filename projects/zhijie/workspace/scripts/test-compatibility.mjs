import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { resolve } from "node:path";
import { validateEvidenceIndex, validateMatrix } from "./compatibility-tool.mjs";

assert.ok(process.argv.includes("--validate-matrix"), "Use --validate-matrix to validate compatibility contracts");
const root = process.cwd();
const matrix = validateMatrix(JSON.parse(readFileSync("release/support-matrix.json", "utf8")), root);
for (const platform of matrix.platforms) {
  assert.equal(platform.installUpdateUninstall, "unverified");
  assert.equal(platform.realVoiceAndTun, "unverified");
  assert.deepEqual(platform.testedGames, []);
}
const invalid = structuredClone(matrix);
invalid.platforms[0].installUpdateUninstall = "verified";
assert.throws(() => validateMatrix(invalid, root), /Missing platform evidence/);

const inventory = JSON.parse(readFileSync("docs/engineering/dependency-inventory.json", "utf8"));
assert.ok(inventory.dependencies.length > 0);
assert.ok(inventory.dependencies.some(item => item.license === "unreviewed"), "License review gaps must remain visible");
const maintenance = readFileSync("docs/engineering/dependency-maintenance.md", "utf8");
assert.match(maintenance, /维护负责人、审核日期、许可证原文/);

const index = validateEvidenceIndex(JSON.parse(readFileSync("release/evidence-index.json", "utf8")), root);
assert.ok(index.entries.some(item => item.category === "audio" && item.status === "unverified"));
assert.ok(index.entries.some(item => item.category === "capacity" && item.status === "unverified"));
const checklist = JSON.parse(readFileSync("release/validation-checklist.json", "utf8"));
for (const category of ["platform", "games", "signing", "audio", "capacity"]) {
  assert.ok(checklist.entries.filter(item => item.category === category).every(item => item.status === "unverified"));
}

const generated = spawnSync(process.execPath, ["scripts/generate-compatibility-report.mjs", "--check"], {
  cwd: root,
  encoding: "utf8",
});
assert.equal(generated.status, 0, generated.stderr);
const diff = JSON.parse(readFileSync(resolve(root, "release/design-asset-diff.json"), "utf8"));
assert.equal(diff.status, "unchanged");
assert.deepEqual([...diff.added, ...diff.removed, ...diff.changed], []);
const release = spawnSync(process.execPath, ["scripts/generate-release-manifest.mjs", "--check"], {
  cwd: root,
  encoding: "utf8",
});
assert.equal(release.status, 0, release.stderr);
console.log("Compatibility runner inputs, evidence index, dependency review gaps and design hashes are consistent; platform, game, signing, audio and capacity acceptance remain unverified.");
