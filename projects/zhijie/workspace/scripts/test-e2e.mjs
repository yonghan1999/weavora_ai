import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { requiredSuites, verifySuiteResults } from "./e2e-suites.mjs";

assert.ok(process.argv.includes("--contract"), "Use --contract for the local desktop E2E suites");
const executable = process.platform === "win32" ? "C:\\Windows\\System32\\cmd.exe" : "./mvnw";
const args = process.platform === "win32" ? ["/d", "/s", "/c", "mvnw.cmd"] : [];
args.push("-B", "-ntp", "-Dstyle.color=never", "-pl", "control-plane", `-Dtest=${requiredSuites.join(",")}`, "test");
const result = spawnSync(executable, args, {
  cwd: process.cwd(),
  env: { ...process.env, ZHIJIE_TEST_GROUP_BROWSER: "true" },
  encoding: "utf8",
  shell: false,
  timeout: 900000,
  maxBuffer: 4 * 1024 * 1024,
});
process.stdout.write(result.stdout ?? "");
process.stderr.write(result.stderr ?? "");
if (result.error) throw result.error;
assert.equal(result.status, 0, "Desktop E2E contract failed");
verifySuiteResults(result.stdout);
