import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, readFileSync, rmdirSync, unlinkSync, writeFileSync, existsSync } from "node:fs";
import { execFileSync, spawnSync } from "node:child_process";
import { dirname, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const check = process.argv.includes("--check");
// 每次从当前 POM 解析真实依赖，不能用旧文本清单给旧 inventory 自证一致。
mkdirSync(resolve(root, "target"), { recursive: true });
const temporary = mkdtempSync(resolve(root, "target/dependency-inventory-"));
const resolvedMaven = resolve(temporary, "maven.txt").replaceAll("\\", "/");
let mavenList;
try {
  const mavenArgs = ["-o", "-q", "-pl", "control-plane", "dependency:list",
    `-DoutputFile=../${relative(root, resolvedMaven).replaceAll("\\", "/")}`,
    "-Dsort=true", "-DappendOutput=false"];
  const maven = spawnSync(process.platform === "win32"
    ? resolve(process.env.SystemRoot ?? "C:/Windows", "System32/cmd.exe") : "./mvnw",
    process.platform === "win32" ? ["/d", "/s", "/c", "mvnw.cmd", ...mavenArgs] : mavenArgs,
    { cwd: root, encoding: "utf8", shell: false,
      maxBuffer: 4 * 1024 * 1024 });
  assert.equal(maven.status, 0, `Maven dependency resolution failed; inventory was not updated\n${maven.stdout}\n${maven.stderr}`);
  mavenList = readFileSync(resolvedMaven, "utf8");
} finally {
  if (existsSync(resolvedMaven)) unlinkSync(resolvedMaven);
  rmdirSync(temporary);
}
const metadata = JSON.parse(execFileSync("cargo", ["metadata", "--format-version", "1", "--locked", "--offline"], {
  cwd: root,
  encoding: "utf8",
  maxBuffer: 32 * 1024 * 1024,
}));

const patched = new Set(["libwebrtc", "webrtc-sys"]);
const dependencies = metadata.packages.filter(item => !patched.has(item.name)).map((item) => ({
  ecosystem: "cargo",
  name: item.name,
  version: item.version,
  license: item.license ?? "unreviewed",
}));

for (const [name, version] of [["libwebrtc", "0.3.55"], ["webrtc-sys", "0.3.52"]]) {
  const path = `vendor/${name}-${version}`;
  dependencies.push({ecosystem: "cargo", name, version, license: "Apache-2.0",
    source: path, localModifications: "native audio track playout volume bindings; structured remove_track status; dedicated local output probe without capture or media publication",
    licenseFile: "vendor/LICENSE-APACHE-2.0", noticeFile: "vendor/webrtc-sys-0.3.52/NOTICE.md"});
}

const npmLock = JSON.parse(readFileSync(resolve(root, "desktop/ui/package-lock.json"), "utf8"));
for (const [path, item] of Object.entries(npmLock.packages)) {
  if (item.link) continue;
  dependencies.push({
    ecosystem: "npm",
    name: item.name ?? (path ? path.split("node_modules/").at(-1) : npmLock.name),
    version: item.version,
    license: item.license ?? "unreviewed",
  });
}

const mavenCoordinates = [];
for (const line of mavenList.split(/\r?\n/)) {
  const plain = line.replace(/\u001b\[[0-9;]*m/g, "").trim();
  const match = plain.match(/^([^:\s]+):([^:\s]+):([^:\s]+):(?:([^:\s]+):)?([^:\s]+):(compile|runtime|test|provided|system)(?:\s|$)/);
  if (!match) continue;
  mavenCoordinates.push(`${match[1]}:${match[2]}:${match[3]}:${match[4] ? `${match[4]}:` : ""}${match[5]}:${match[6]}`);
  dependencies.push({
    ecosystem: "maven",
    name: `${match[1]}:${match[2]}`,
    version: match[5],
    license: "unreviewed",
    scope: match[6],
  });
}
assert.ok(mavenCoordinates.length > 0, "Maven dependency output contains no recognized coordinates");
const mavenOutput = `${mavenCoordinates.sort().join("\n")}\n`;
const mavenPath = resolve(root, "docs/engineering/maven-dependencies.txt");

dependencies.sort((left, right) =>
  `${left.ecosystem}:${left.name}`.localeCompare(`${right.ecosystem}:${right.name}`),
);

const inventory = {
  schemaVersion: 1,
  reviewStatus: "unreviewed entries require license review before release",
  dependencies,
};
const inventoryPath = resolve(root, "docs/engineering/dependency-inventory.json");
const output = `${JSON.stringify(inventory, null, 2)}\n`;
if (check) {
  assert.ok(readFileSync(mavenPath, "utf8") === mavenOutput, "Maven dependency snapshot is stale; regenerate inventory");
  assert.ok(readFileSync(inventoryPath, "utf8") === output, "Dependency inventory is stale; regenerate inventory");
} else {
  writeFileSync(mavenPath, mavenOutput);
  writeFileSync(inventoryPath, output);
}
console.log(`${check ? "Checked" : "Recorded"} ${dependencies.length} dependencies against current Maven, Cargo and npm inputs; license review remains open.`);
