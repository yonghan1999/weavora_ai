import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { spawnSync } from "node:child_process";

assert.ok(process.argv.includes("--contract"), "Use --contract to validate release configuration");
const tauri = JSON.parse(readFileSync("desktop/shell/tauri.conf.json", "utf8"));
assert.equal(tauri.bundle.active, true);
assert.equal(tauri.bundle.createUpdaterArtifacts, false);
assert.ok(tauri.bundle.targets.includes("nsis"));
assert.ok(tauri.bundle.targets.includes("dmg"));
assert.ok(tauri.bundle.targets.includes("appimage"));

const linux = JSON.parse(readFileSync("desktop/shell/tauri.linux.conf.json", "utf8"));
assert.equal(linux.productName, "zhijie", "Keep the Debian package identity stable across upgrades");
assert.deepEqual(linux.bundle.targets, ["deb", "appimage"]);
const desktopEntry = readFileSync(resolve("desktop/shell", linux.bundle.linux.deb.desktopTemplate), "utf8");
for (const line of ["[Desktop Entry]", "Type=Application", `Name[zh_CN]=${tauri.productName}`, "Exec={{exec}}", "Icon={{icon}}", "Terminal=false"]) {
  assert.ok(desktopEntry.split(/\r?\n/).includes(line), `Missing Linux desktop entry: ${line}`);
}

const windows = JSON.parse(readFileSync("desktop/shell/tauri.windows.conf.json", "utf8"));
assert.deepEqual(windows.bundle.targets, ["nsis"]);
assert.equal(windows.bundle.windows.nsis.installMode, "perMachine");
assert.equal(windows.bundle.windows.nsis.installerHooks, "../../packaging/windows/installer-hooks.nsh");
assert.deepEqual(windows.bundle.resources, {
  "../../target/windows-bundle/zhijie-helper.exe": "zhijie-helper.exe",
  "../../scripts/local-preview/start-helper.ps1": "start-helper.ps1",
  "../../scripts/local-preview/HelperInstallPolicy.psm1": "HelperInstallPolicy.psm1",
  "../../target/windows-bundle/wintun.dll": "wintun.dll",
  "../../target/windows-bundle/WINTUN-LICENSE.txt": "WINTUN-LICENSE.txt",
  "../../target/windows-bundle/wintun.h": "wintun.h",
  "../../target/windows-bundle/wintun-source.json": "wintun-source.json",
});
const macAudio = spawnSync(process.platform === "win32" ? "python" : "python3", ["-B", "packaging/macos/check-audio-bundle.py"], {encoding: "utf8"});
assert.equal(macAudio.status, 0, macAudio.error?.message ?? macAudio.stderr);
const preview = JSON.parse(readFileSync("packaging/windows/preview.conf.json", "utf8"));
assert.equal(preview.bundle.resources, undefined, "Preview resources must derive from the Windows configuration");
assert.equal(preview.bundle.windows.nsis.installMode, "currentUser");

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
