import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

const separator = process.argv.indexOf("--");
const platformIndex = process.argv.indexOf("--platform");
const outputIndex = process.argv.indexOf("--output");
assert.ok(separator > 0 && platformIndex > 0 && outputIndex > 0, "Usage: node scripts/run-compatibility.mjs --platform windows --output result.json -- adapter [args]");
const platform = process.argv[platformIndex + 1];
assert.ok(["windows", "macos", "linux"].includes(platform));
const adapter = process.argv.slice(separator + 1);
assert.ok(adapter.length > 0, "A real platform adapter command is required");
const output = resolve(process.argv[outputIndex + 1]);
mkdirSync(dirname(output), { recursive: true });
const startedAt = new Date().toISOString();
const result = spawnSync(adapter[0], adapter.slice(1), {
  cwd: process.cwd(),
  env: process.env,
  encoding: "utf8",
  shell: false,
  maxBuffer: 16 * 1024 * 1024,
});
if (result.error) throw result.error;
writeFileSync(`${output}.stdout.log`, result.stdout ?? "");
writeFileSync(`${output}.stderr.log`, result.stderr ?? "");
writeFileSync(output, `${JSON.stringify({
  schemaVersion: 1,
  platform,
  startedAt,
  command: adapter,
  exitCode: result.status,
  evidence: [`${output}.stdout.log`, `${output}.stderr.log`],
  status: "adapter_recorded_not_acceptance_verified",
  note: "Installation, signing, real audio/TUN, games, capacity and cross-platform acceptance require human-reviewed evidence",
}, null, 2)}\n`);
console.log(`Recorded ${platform} adapter output; support-matrix status was not changed.`);
