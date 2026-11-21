import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { closeSync, fstatSync, openSync, readSync, realpathSync } from "node:fs";
import { isAbsolute, relative, resolve, sep } from "node:path";

const metricNames = [
  "cpuPeakPercent",
  "memoryPeakMiB",
  "upstreamPeakKbitPerSecond",
  "downstreamPeakKbitPerSecond",
  "operationSuccessRatePercent",
  "p95LatencyMs",
  "sampleCount",
];

export function validateScenario(scenario) {
  assert.match(scenario.id, /^C[0-4]$/, "Unknown capacity scenario");
  assert.ok(scenario.capacityClass);
  assert.ok(Number.isInteger(scenario.accounts) && scenario.accounts >= 0);
  assert.ok(Number.isInteger(scenario.durationSeconds) && scenario.durationSeconds > 0);
  assert.ok(Array.isArray(scenario.rooms));
  assert.ok(Array.isArray(scenario.workloads) && scenario.workloads.length > 0);
  assert.ok(Array.isArray(scenario.evidenceRequirements) && scenario.evidenceRequirements.length > 0);
  const roomAccounts = scenario.rooms.reduce((total, room) => total + room.members * (room.count ?? 1), 0);
  assert.ok(roomAccounts <= scenario.accounts, `${scenario.id} room membership exceeds accounts`);
  return scenario;
}

export function scenarioById(configuration, id) {
  const scenario = configuration.scenarios.find(item => item.id === id);
  assert.ok(scenario, `Unknown scenario: ${id}`);
  return validateScenario(scenario);
}

export function budgetFailures(result, scenario, budget) {
  const failures = [];
  const metrics = result.metrics;
  if (!metrics) return ["metrics-missing"];
  if (metrics.memoryPeakMiB > budget.memoryMiB) failures.push("memory");
  if (metrics.upstreamPeakKbitPerSecond > budget.upstreamKbitPerSecond) failures.push("upstream");
  if (metrics.downstreamPeakKbitPerSecond > budget.downstreamKbitPerSecond) failures.push("downstream");
  if (metrics.cpuPeakPercent > budget.cpuCores * 100) failures.push("cpu");
  if (result.execution.durationSeconds < scenario.durationSeconds) failures.push("duration");
  const thresholds = scenario.successThresholds;
  if (thresholds) {
    if (metrics.operationSuccessRatePercent < thresholds.operationSuccessRatePercent) failures.push("success-rate");
    if (metrics.p95LatencyMs > thresholds.p95LatencyMs) failures.push("latency");
  }
  return failures;
}

function verifyEvidence(entries, requirements, directory) {
  assert.ok(directory, "A pass requires the evidence directory for this run");
  const root = realpathSync(directory);
  const covered = new Set();
  const hashes = new Map();
  const buffer = Buffer.allocUnsafe(64 * 1024);
  for (const entry of entries) {
    assert.ok(typeof entry?.category === "string" && entry.category.length > 0, "Missing evidence category");
    assert.ok(typeof entry.path === "string" && entry.path && !isAbsolute(entry.path), "Expected a relative evidence path");
    assert.match(entry.sha256 ?? "", /^[a-f0-9]{64}$/, "Expected evidence SHA-256");
    const file = realpathSync(resolve(root, entry.path));
    const local = relative(root, file);
    assert.ok(local && local !== ".." && !local.startsWith(`..${sep}`) && !isAbsolute(local), "Evidence outside run directory");
    if (!hashes.has(file)) {
      const descriptor = openSync(file, "r");
      try {
        const stat = fstatSync(descriptor);
        assert.ok(stat.isFile() && stat.size > 0, "Expected a non-empty evidence file");
        const hash = createHash("sha256");
        let count;
        while ((count = readSync(descriptor, buffer)) > 0) hash.update(buffer.subarray(0, count));
        hashes.set(file, hash.digest("hex"));
      } finally { closeSync(descriptor); }
    }
    assert.equal(hashes.get(file), entry.sha256, `Evidence hash mismatch: ${entry.path}`);
    covered.add(entry.category);
  }
  for (const category of requirements) assert.ok(covered.has(category), `Missing evidence category: ${category}`);
}

export function validateResult(result, scenario, budget, evidenceDirectory) {
  assert.equal(result.schemaVersion, 1);
  assert.equal(result.scenarioId, scenario.id);
  assert.ok(["not_run", "running", "completed", "failed"].includes(result.execution.status));
  assert.ok(Number.isFinite(result.execution.durationSeconds) && result.execution.durationSeconds >= 0);
  assert.ok(Array.isArray(result.evidence));
  assert.ok(result.conclusion && ["not_run", "metrics_required", "failed", "passed"].includes(result.conclusion.status));
  if (result.execution.status === "not_run") {
    assert.equal(result.conclusion.status, "not_run", "Unexecuted scenarios cannot have a pass conclusion");
    assert.equal(result.metrics, null);
    assert.equal(result.evidence.length, 0);
  }
  if (result.conclusion.status === "passed") {
    assert.equal(result.execution.status, "completed", "A pass requires a completed real execution");
    assert.ok(result.execution.command?.length > 0, "A pass requires the exact load command");
    for (const name of metricNames) {
      assert.ok(Number.isFinite(result.metrics?.[name]) && result.metrics[name] >= 0, `Missing metric: ${name}`);
    }
    assert.ok(result.metrics.sampleCount > 0, "A pass requires real samples");
    assert.deepEqual(budgetFailures(result, scenario, budget), [], "A pass cannot exceed scenario or resource budgets");
    verifyEvidence(result.evidence, scenario.evidenceRequirements, evidenceDirectory);
  }
  return result;
}
