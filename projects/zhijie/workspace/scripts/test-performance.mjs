import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { budgetFailures, scenarioById, validateResult, validateScenario } from "./performance-tool.mjs";

assert.ok(process.argv.includes("--validate-fixtures"), "Use --validate-fixtures to validate performance contracts");
const configuration = JSON.parse(readFileSync("scripts/performance-scenarios.json", "utf8"));
assert.equal(configuration.schemaVersion, 1);
assert.deepEqual(configuration.resourceBudget, {
  cpuCores: 2,
  memoryMiB: 2048,
  upstreamKbitPerSecond: 5000,
  downstreamKbitPerSecond: 5000,
});
assert.deepEqual(configuration.scenarios.map(item => item.id), ["C0", "C1", "C2", "C3", "C4"]);
configuration.scenarios.forEach(validateScenario);
const c1 = scenarioById(configuration, "C1");
const c2 = scenarioById(configuration, "C2");
const c3 = scenarioById(configuration, "C3");
assert.equal(c1.accounts, 100);
assert.equal(c1.rooms.length, 0);
assert.equal(c2.accounts, 12);
assert.deepEqual(c2.speakerCounts, [1, 2, 12]);
assert.equal(c3.accounts, 100);
assert.equal(c3.rooms.reduce((sum, room) => sum + room.members * (room.count ?? 1), 0), 100);
assert.equal(new Set([c1.capacityClass, c2.capacityClass, c3.capacityClass]).size, 3, "Online, full-room and distributed-room results are not interchangeable");
assert.deepEqual(scenarioById(configuration, "C4").requiresMeasuredThresholdFrom, ["C1", "C2", "C3"]);

const fixtures = JSON.parse(readFileSync("scripts/performance-fixtures.json", "utf8"));
for (const result of fixtures.results) {
  validateResult(result, scenarioById(configuration, result.scenarioId), configuration.resourceBudget);
}
const unmeasuredPass = structuredClone(fixtures.results[0]);
unmeasuredPass.conclusion.status = "passed";
assert.throws(() => validateResult(unmeasuredPass, c1, configuration.resourceBudget), /Unexecuted scenarios cannot have a pass conclusion/);
const shortRun = structuredClone(fixtures.results[0]);
shortRun.execution = {status: "completed", durationSeconds: 1, command: ["adapter"]};
shortRun.conclusion.status = "passed";
shortRun.metrics = {
  cpuPeakPercent: 100,
  memoryPeakMiB: 1024,
  upstreamPeakKbitPerSecond: 100,
  downstreamPeakKbitPerSecond: 100,
  operationSuccessRatePercent: 100,
  p95LatencyMs: 100,
  sampleCount: 100,
};
shortRun.evidence = c1.evidenceRequirements;
assert.throws(() => validateResult(shortRun, c1, configuration.resourceBudget), /budgets/);
assert.deepEqual(budgetFailures(shortRun, c1, configuration.resourceBudget), ["duration"]);
const linkBoundary = structuredClone(shortRun);
linkBoundary.execution.durationSeconds = c1.durationSeconds;
linkBoundary.metrics.upstreamPeakKbitPerSecond = 5000;
linkBoundary.metrics.downstreamPeakKbitPerSecond = 5000;
assert.deepEqual(budgetFailures(linkBoundary, c1, configuration.resourceBudget), []);
for (const direction of ["upstream", "downstream"]) {
  const metric = `${direction}PeakKbitPerSecond`;
  linkBoundary.metrics[metric] = 5000.001;
  assert.deepEqual(budgetFailures(linkBoundary, c1, configuration.resourceBudget), [direction]);
  assert.throws(() => validateResult(linkBoundary, c1, configuration.resourceBudget), /budgets/);
  linkBoundary.metrics[metric] = 5000;
}
mkdirSync("target", {recursive: true});
const directory = mkdtempSync(join("target", "capacity-evidence-fixture-"));
try {
  const verified = structuredClone(linkBoundary);
  verified.evidence = c1.evidenceRequirements.map(category => {
    const path = `${category}.log`;
    const contents = `fixture: ${category}\n`.repeat(6000);
    writeFileSync(join(directory, path), contents);
    return {category, path, sha256: createHash("sha256").update(contents).digest("hex")};
  });
  const check = evidence => validateResult({...verified, evidence}, c1, configuration.resourceBudget, directory);
  check(verified.evidence);
  const first = verified.evidence[0];
  assert.throws(() => check(c1.evidenceRequirements), /evidence category/);
  assert.throws(() => check(verified.evidence.map(() => first)), /Missing evidence category/);
  assert.throws(() => check([{...first, path: ".."}]), /outside run directory/);
  assert.throws(() => check([{...first, path: "missing.log"}]), {code: "ENOENT"});
  writeFileSync(join(directory, first.path), "changed");
  assert.throws(() => check(verified.evidence), /hash mismatch/);
  writeFileSync(join(directory, first.path), "");
  assert.throws(() => check(verified.evidence), /non-empty evidence/);
} finally { rmSync(directory, {recursive: true, force: true}); }
console.log("C0-C4 parameters, result schemas, long-session budgets and audio evidence requirements are valid; all capacity acceptance remains unverified.");
