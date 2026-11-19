import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { resolve } from "node:path";
import { reserveValidationOutput } from "./validation-output.mjs";

const separator = process.argv.indexOf("--");
const platformIndex = process.argv.indexOf("--platform");
const outputIndex = process.argv.indexOf("--output");
assert.ok(separator > 0 && platformIndex > 0 && outputIndex > 0, "Usage: node scripts/run-compatibility.mjs --platform windows --output result.json -- adapter [args]");
const platform = process.argv[platformIndex + 1];
assert.ok(["windows", "macos", "linux"].includes(platform));
const adapter = process.argv.slice(separator + 1);
assert.ok(adapter.length > 0, "A real platform adapter command is required");
const output = resolve(process.argv[outputIndex + 1]);
const record = reserveValidationOutput(output);
const startedAt = new Date().toISOString();
const result = spawnSync(adapter[0], adapter.slice(1), {
  cwd: process.cwd(),
  env: process.env,
  encoding: "utf8",
  shell: false,
  maxBuffer: 16 * 1024 * 1024,
});
record.writeLogs(result.stdout, result.stderr);
record.writeReport({
  schemaVersion: 1,
  platform,
  startedAt,
  command: adapter,
  exitCode: result.status,
  signal: result.signal,
  errorCode: result.error?.code ?? null,
  evidence: record.evidence,
  status: result.status === 0 && !result.error ? "adapter_recorded_not_acceptance_verified" : "failed",
  note: "Installation, signing, real audio/TUN, games, capacity and cross-platform acceptance require human-reviewed evidence",
});
record.close();
console.log(`Recorded ${platform} adapter output; support-matrix status was not changed.`);
process.exitCode = result.status === 0 && !result.error ? 0 : result.status || 1;
