import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { requiredSuites, verifySuiteResults } from "./e2e-suites.mjs";

const [mode] = process.argv.slice(2);
assert.ok(process.argv.length === 3 && ["--contract", "--room-invitation", "--external-room-link"].includes(mode), "Choose one desktop E2E suite");
const invitation = mode === "--room-invitation", external = mode === "--external-room-link";
const suite = "app.weavora.zhijie.controlplane.group.GroupBrowserIntegrationTest";
const filter = invitation ? `${suite}#sharedInvitationTakesTheIntendedPlayerToTheNamedRoom` : external ? `${suite}#externallyOpenedRoomInvitationRequiresOnlyOneConfirmation` : requiredSuites.join(",");
const executable = process.platform === "win32" ? "C:\\Windows\\System32\\cmd.exe" : "./mvnw";
const args = process.platform === "win32" ? ["/d", "/s", "/c", "mvnw.cmd"] : [];
args.push("-B", "-ntp", "-Dstyle.color=never", "-pl", "control-plane", `-Dtest=${filter}`, "test");
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
verifySuiteResults(result.stdout, invitation || external ? [suite] : requiredSuites, invitation ? 3 : external ? 1 : undefined);
