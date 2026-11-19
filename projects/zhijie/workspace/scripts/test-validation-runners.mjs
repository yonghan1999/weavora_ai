import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, existsSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { machine, release } from "node:os";
import { resolve } from "node:path";

const directory = mkdtempSync(resolve("target/validation-runners-"));
const platforms = { win32: "windows", darwin: "macos", linux: "linux" };
const localPlatform = platforms[process.platform];
for (const [name, selector] of [["compatibility", ["--platform", localPlatform]], ["performance", ["--scenario", "C0"]]]) {
  for (const [condition, command, expected] of [
    ["success", [process.execPath, "-e", "process.stdout.write('adapter-output');process.stderr.write('adapter-diagnostic')"], 0],
    ["failure", [process.execPath, "-e", "process.stdout.write('before-failure');process.exit(7)"], 7],
    ["missing", [resolve(directory, "missing-adapter.exe")], 1],
  ]) {
    const output = resolve(directory, `${name}-${condition}.json`);
    const result = spawnSync(process.execPath, [`scripts/run-${name}.mjs`, ...selector, "--output", output, "--", ...command], {encoding: "utf8"});
    assert.equal(result.status, expected, `${name}/${condition}: wrapper must preserve adapter failure`);
    assert.ok(existsSync(output), `${name}/${condition}: failure must retain structured result`);
    const recorded = JSON.parse(readFileSync(output, "utf8"));
    if (name === "compatibility") {
      assert.equal(recorded.platform, localPlatform);
      assert.deepEqual(recorded.host, { platform: process.platform, nodeArch: process.arch, machine: machine(), kernelRelease: release() });
      if (condition === "success") assert.equal(recorded.status, "adapter_recorded_not_acceptance_verified");
    }
    assert.notEqual(recorded.conclusion?.status, "passed");
    const exitCode = name === "performance" ? recorded.execution.exitCode : recorded.exitCode;
    assert.equal(exitCode, condition === "missing" ? null : expected);
    assert.ok(recorded.evidence.every(path => existsSync(path)));
    if (condition === "success") {
      assert.equal(readFileSync(`${output}.stdout.log`, "utf8"), "adapter-output");
      assert.equal(readFileSync(`${output}.stderr.log`, "utf8"), "adapter-diagnostic");
    } else {
      assert.equal(name === "performance" ? recorded.conclusion.status : recorded.status, "failed");
      if (condition === "missing") {
        assert.equal(name === "performance" ? recorded.execution.errorCode : recorded.errorCode, "ENOENT");
      } else {
        assert.equal(readFileSync(`${output}.stdout.log`, "utf8"), "before-failure");
      }
    }
  }
  const marker = resolve(directory, `${name}-unexpected-adapter-start`);
  const rerun = (output, selected = selector) => spawnSync(process.execPath, [`scripts/run-${name}.mjs`, ...selected, "--output", output,
    "--", process.execPath, "-e", "require('node:fs').writeFileSync(process.argv[1], 'ran')", marker], {encoding: "utf8"});
  const failedOutput = resolve(directory, `${name}-failure.json`);
  const artifacts = [failedOutput, `${failedOutput}.stdout.log`, `${failedOutput}.stderr.log`];
  const previous = artifacts.map(path => readFileSync(path));
  assert.notEqual(rerun(failedOutput).status, 0);
  assert.deepEqual(artifacts.map(path => readFileSync(path)), previous);
  for (const suffix of [".stdout.log", ".stderr.log"]) {
    const output = resolve(directory, `${name}-partial${suffix}.json`);
    writeFileSync(output + suffix, "previous run", {flag: "wx"});
    assert.notEqual(rerun(output).status, 0);
    assert.equal(readFileSync(output + suffix, "utf8"), "previous run");
    assert.equal(existsSync(output), false);
    assert.equal(existsSync(output + (suffix === ".stdout.log" ? ".stderr.log" : ".stdout.log")), false);
  }
  assert.equal(existsSync(marker), false, "An output conflict must stop the adapter before it starts");
  if (name === "compatibility") {
    for (const platform of Object.values(platforms).filter(value => value !== localPlatform)) {
      const output = resolve(directory, `wrong-${platform}.json`);
      const rejected = rerun(output, ["--platform", platform]);
      assert.notEqual(rejected.status, 0);
      assert.match(rejected.stderr, /Run the compatibility wrapper on the target OS/);
      for (const path of [marker, output, `${output}.stdout.log`, `${output}.stderr.log`]) assert.equal(existsSync(path), false);
    }
  }
}
for (const code of [0, 7]) {
  const output = resolve(directory, `metrics-missing-${code}.json`);
  const result = spawnSync(process.execPath, ["scripts/run-performance.mjs", "--scenario", "C0", "--output", output,
    "--metrics", resolve(directory, "missing-metrics.json"), "--", process.execPath, "-e", `process.exit(${code})`], {encoding: "utf8"});
  assert.equal(result.status, code || 1, "Unreadable metrics must not lose adapter failure or report success");
  const recorded = JSON.parse(readFileSync(output, "utf8"));
  assert.equal(recorded.metricsError, "METRICS_UNREADABLE");
  assert.equal(recorded.conclusion.status, "failed");
  assert.equal(recorded.execution.exitCode, code);
  assert.ok(recorded.evidence.every(path => existsSync(path)));
}
console.log("Validation runners preserve adapter exit codes and failure evidence; success does not imply acceptance.");
