import assert from "node:assert/strict";
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { designAssetDiff, trackedDesignAssets } from "./compatibility-tool.mjs";

const root = process.cwd();
const baselinePath = resolve(root, "release/design-asset-baseline.json");
const diffPath = resolve(root, "release/design-asset-diff.json");
const current = trackedDesignAssets(root);
if (process.argv.includes("--update-baseline")) {
  writeFileSync(baselinePath, `${JSON.stringify({schemaVersion: 1, source: "approved B1/B2 tracked design assets", assets: current}, null, 2)}\n`);
}
const baseline = JSON.parse(readFileSync(baselinePath, "utf8"));
const output = `${JSON.stringify(designAssetDiff(baseline, current), null, 2)}\n`;
if (process.argv.includes("--check")) {
  assert.equal(readFileSync(diffPath, "utf8"), output, "Design asset difference report is stale");
} else {
  writeFileSync(diffPath, output);
}
console.log(`Design asset comparison: ${JSON.parse(output).status}; ${current.length} tracked files checked.`);
