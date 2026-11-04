import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";

assert.ok(process.argv.includes("--contract"), "Use --contract for the local vertical slice");
const executable = process.platform === "win32" ? "C:\\Windows\\System32\\cmd.exe" : "./mvnw";
const args = process.platform === "win32"
  ? ["/d", "/s", "/c", "mvnw.cmd", "-q", "-pl", "control-plane", "-am", "-Dtest=VerticalSliceIntegrationTest", "test"]
  : ["-q", "-pl", "control-plane", "-am", "-Dtest=VerticalSliceIntegrationTest", "test"];
const result = spawnSync(executable, args, {
  cwd: process.cwd(),
  env: process.env,
  encoding: "utf8",
  shell: false,
  timeout: 300000,
  maxBuffer: 4 * 1024 * 1024,
});
if (result.error) throw result.error;
process.stdout.write(result.stdout ?? "");
process.stderr.write(result.stderr ?? "");
assert.equal(result.status, 0, "Vertical slice failed");
