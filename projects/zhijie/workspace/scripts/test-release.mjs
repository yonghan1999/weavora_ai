import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";

assert.ok(process.argv.includes("--contract"), "Use --contract to validate release configuration");
const tauri = JSON.parse(readFileSync("desktop/shell/tauri.conf.json", "utf8"));
assert.equal(tauri.bundle.active, true);
assert.equal(tauri.bundle.createUpdaterArtifacts, false);
assert.ok(tauri.bundle.targets.includes("nsis"));
assert.ok(tauri.bundle.targets.includes("dmg"));
assert.ok(tauri.bundle.targets.includes("appimage"));

const policy = JSON.parse(readFileSync("packaging/install-policy.json", "utf8"));
assert.equal(policy.upgradeRequiresCompatibleHelper, true);
assert.equal(policy.uninstall.removeDefaultRoute, false);
assert.equal(policy.uninstall.removePhysicalLanRoutes, false);
assert.equal(policy.uninstall.deleteServerData, false);
assert.equal(policy.productionSigningAuthorized, false);

const matrix = JSON.parse(readFileSync("release/support-matrix.json", "utf8"));
assert.deepEqual(matrix.platforms.map(item => item.platform).sort(), ["linux", "macos", "windows"]);
for (const platform of matrix.platforms) {
  assert.ok(Array.isArray(platform.evidence));
  if (platform.installUpdateUninstall === "verified" || platform.realVoiceAndTun === "verified") {
    assert.ok(platform.evidence.length > 0, `Missing evidence for ${platform.platform}`);
  }
}

const generated = spawnSync(process.execPath, ["scripts/generate-release-manifest.mjs", "--check"], {
  encoding: "utf8",
});
assert.equal(generated.status, 0, generated.stderr);
console.log("Release configuration, support matrix, and artifact hashes are consistent; installers remain unverified.");
