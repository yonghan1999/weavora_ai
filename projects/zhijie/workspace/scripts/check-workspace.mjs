import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const required = [
  "pom.xml",
  "mvnw",
  ".mvn/wrapper/maven-wrapper.properties",
  "control-plane/pom.xml",
  "Cargo.toml",
  "Cargo.lock",
  "desktop/ui/package.json",
  "desktop/ui/package-lock.json",
  "docs/engineering/maven-dependencies.txt",
  "docs/engineering/dependency-inventory.json",
];
for (const path of required) {
  assert.ok(existsSync(resolve(root, path)), `Missing required workspace file: ${path}`);
}

const cargo = spawnSync("cargo", ["metadata", "--format-version", "1", "--no-deps"], {
  cwd: root,
  encoding: "utf8",
});
assert.equal(cargo.status, 0, cargo.stderr);
const packageNames = JSON.parse(cargo.stdout).packages.map((item) => item.name).sort();
assert.deepEqual(packageNames, ["zhijie-core", "zhijie-helper", "zhijie-relay", "zhijie-shell", "zhijie-voice"]);

const mavenExecutable = process.platform === "win32" ? "mvnw.cmd" : "./mvnw";
const maven = spawnSync(
  mavenExecutable,
  ["-q", "-pl", "control-plane", "-am", "-DskipTests", "validate"],
  { cwd: root, env: process.env, encoding: "utf8", shell: process.platform === "win32" },
);
assert.equal(maven.status, 0, `${maven.stdout}\n${maven.stderr}`);

const inventory = JSON.parse(readFileSync(resolve(root, "docs/engineering/dependency-inventory.json"), "utf8"));
assert.equal(inventory.schemaVersion, 1);
assert.ok(inventory.dependencies.some((item) => item.ecosystem === "cargo"));
assert.ok(inventory.dependencies.some((item) => item.ecosystem === "npm"));
assert.ok(inventory.dependencies.some((item) => item.ecosystem === "maven"));

const pom = readFileSync(resolve(root, "pom.xml"), "utf8");
assert.match(pom, /<java\.version>(2[1-9]|[3-9][0-9])<\/java\.version>/);
console.log("Workspace checks passed: Maven, Cargo, npm, locks, and dependency inventory.");
