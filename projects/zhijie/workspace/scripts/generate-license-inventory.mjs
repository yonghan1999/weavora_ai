import { readFileSync, writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const metadata = JSON.parse(execFileSync("cargo", ["metadata", "--format-version", "1", "--no-deps"], {
  cwd: root,
  encoding: "utf8",
}));

const dependencies = metadata.packages.map((item) => ({
  ecosystem: "cargo",
  name: item.name,
  version: item.version,
  license: item.license ?? "unreviewed",
}));

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

const mavenList = readFileSync(resolve(root, "docs/engineering/maven-dependencies.txt"), "utf8");
for (const line of mavenList.split(/\r?\n/)) {
  const plain = line.replace(/\u001b\[[0-9;]*m/g, "").trim();
  const match = plain.match(/^([^:\s]+):([^:\s]+):([^:\s]+):([^:\s]+):(compile|runtime|test)(?:\s|$)/);
  if (!match) continue;
  dependencies.push({
    ecosystem: "maven",
    name: `${match[1]}:${match[2]}`,
    version: match[4],
    license: "unreviewed",
  });
}

dependencies.sort((left, right) =>
  `${left.ecosystem}:${left.name}`.localeCompare(`${right.ecosystem}:${right.name}`),
);

const inventory = {
  schemaVersion: 1,
  reviewStatus: "unreviewed entries require license review before release",
  dependencies,
};
writeFileSync(
  resolve(root, "docs/engineering/dependency-inventory.json"),
  `${JSON.stringify(inventory, null, 2)}\n`,
);
console.log(`Recorded ${dependencies.length} dependencies.`);
