import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const generator = fileURLToPath(new URL("./generate-compatibility-report.mjs", import.meta.url));

test("design report checks and invalid invocations never write the baseline or report", context => {
  const cwd = mkdtempSync(join(tmpdir(), "design-report-"));
  context.after(() => rmSync(cwd, { recursive: true, force: true }));
  assert.equal(spawnSync("git", ["init", "-q"], { cwd }).status, 0);
  const release = join(cwd, "release");
  mkdirSync(release);
  const baseline = join(release, "design-asset-baseline.json");
  const report = join(release, "design-asset-diff.json");
  const original = '{"schemaVersion":1,"assets":[]}';
  writeFileSync(baseline, original);
  writeFileSync(report, "stale");
  const run = args => spawnSync(process.execPath, [generator, ...args], { cwd, encoding: "utf8" });
  for (const args of [["--check", "--update-baseline"], ["--update-baseline", "--check"], ["--chek"], ["--check", "--check"]]) {
    const result = run(args);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /Usage:/);
    assert.equal(readFileSync(baseline, "utf8"), original);
    assert.equal(readFileSync(report, "utf8"), "stale");
  }
  const stale = run(["--check"]);
  assert.equal(stale.status, 1);
  assert.match(stale.stderr, /report is stale/);
  assert.equal(readFileSync(baseline, "utf8"), original);
  assert.equal(readFileSync(report, "utf8"), "stale");
  assert.equal(run([]).status, 0);
  const generated = readFileSync(report);
  assert.equal(run(["--check"]).status, 0);
  assert.deepEqual(readFileSync(report), generated);
  assert.equal(readFileSync(baseline, "utf8"), original);
  assert.equal(run(["--update-baseline"]).status, 0);
  assert.deepEqual(JSON.parse(readFileSync(baseline, "utf8")).assets, []);
  assert.notEqual(readFileSync(baseline, "utf8"), original);
  assert.equal(run(["--check"]).status, 0);
});
