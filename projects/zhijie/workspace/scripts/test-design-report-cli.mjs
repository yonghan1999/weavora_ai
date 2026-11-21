import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const generator = fileURLToPath(new URL("./generate-compatibility-report.mjs", import.meta.url));

test("unstaged edits and deletions cannot pass an index-based design check", context => {
  const cwd = mkdtempSync(join(tmpdir(), "design-index-"));
  context.after(() => rmSync(cwd, { recursive: true, force: true }));
  const run = (...args) => spawnSync(process.execPath, [generator, ...args], { cwd, encoding: "utf8" });
  assert.equal(spawnSync("git", ["init", "-q"], { cwd }).status, 0);
  mkdirSync(join(cwd, "design/ui"), { recursive: true });
  mkdirSync(join(cwd, "release"));
  const asset = join(cwd, "design/ui/index.html");
  const baseline = join(cwd, "release/design-asset-baseline.json");
  const report = join(cwd, "release/design-asset-diff.json");
  writeFileSync(join(cwd, ".gitattributes"), "*.html text eol=lf\n");
  writeFileSync(asset, "approved\n");
  assert.equal(spawnSync("git", ["add", "--", ".gitattributes", "design"], { cwd }).status, 0);
  assert.equal(run("--update-baseline").status, 0);
  const originalBaseline = readFileSync(baseline);
  const originalReport = readFileSync(report);
  writeFileSync(asset, "approved\r\n");
  writeFileSync(join(cwd, "design/ui/ignored-shot.png"), "not tracked");
  assert.equal(run("--check").status, 0);
  writeFileSync(asset, "changed locally\n");
  for (const remove of [false, true]) {
    if (remove) rmSync(asset);
    for (const args of [["--check"], [], ["--update-baseline"]]) {
      const result = run(...args);
      assert.notEqual(result.status, 0);
      assert.match(result.stderr, /design\/ui\/index\.html/);
      assert.deepEqual(readFileSync(baseline), originalBaseline);
      assert.deepEqual(readFileSync(report), originalReport);
    }
  }
});

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
