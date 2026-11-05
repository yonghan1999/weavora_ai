import { createHash } from "node:crypto";
import { readFileSync, statSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const root = process.cwd();
const files = [
  "desktop/shell/tauri.conf.json",
  "packaging/install-policy.json",
  "packaging/windows/helper-install.json",
  "packaging/macos/app.weavora.zhijie.helper.plist",
  "packaging/linux/zhijie-helper.service",
  "release/support-matrix.json",
];
const artifacts = files.map(path => {
  const content = readFileSync(resolve(root, path));
  return {
    path,
    artifactClass: "configuration",
    bytes: statSync(resolve(root, path)).size,
    sha256: createHash("sha256").update(content).digest("hex"),
  };
});
const manifest = {
  schemaVersion: 1,
  version: "0.1.0",
  clientHelperProtocol: 1,
  artifactClass: "release_configuration_not_installer",
  artifacts,
};
const output = `${JSON.stringify(manifest, null, 2)}\n`;
const path = resolve(root, "release/manifest.json");
if (process.argv.includes("--check")) {
  const current = readFileSync(path, "utf8");
  if (current !== output) throw new Error("Release manifest is stale");
} else {
  writeFileSync(path, output);
  console.log(`Recorded ${artifacts.length} configuration files.`);
}
